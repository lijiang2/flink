/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.flink.hadoopcompatibility.mapreduce;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.apache.flink.api.common.io.FinalizeOnMaster;
import org.apache.flink.api.common.io.OutputFormat;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.hadoopcompatibility.mapreduce.utils.HadoopUtils;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.JobID;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.lib.output.FileOutputCommitter;


public class HadoopOutputFormat<K extends Writable,V extends Writable> implements OutputFormat<Tuple2<K, V>>, FinalizeOnMaster {
	
	private static final long serialVersionUID = 1L;
	
	private org.apache.hadoop.conf.Configuration configuration;
	private org.apache.hadoop.mapreduce.OutputFormat<K,V> mapreduceOutputFormat;
	private transient RecordWriter<K,V> recordWriter;
	private transient FileOutputCommitter fileOutputCommitter;
	private transient TaskAttemptContext context;
	private transient int taskNumber;
	
	public HadoopOutputFormat(org.apache.hadoop.mapreduce.OutputFormat<K,V> mapreduceOutputFormat, Job job) {
		super();
		this.mapreduceOutputFormat = mapreduceOutputFormat;
		this.configuration = job.getConfiguration();
		HadoopUtils.mergeHadoopConf(configuration);
	}
	
	public void setConfiguration(org.apache.hadoop.conf.Configuration configuration) {
		this.configuration = configuration;
	}
	
	public org.apache.hadoop.conf.Configuration getConfiguration() {
		return this.configuration;
	}
	
	public org.apache.hadoop.mapreduce.OutputFormat<K,V> getHadoopOutputFormat() {
		return this.mapreduceOutputFormat;
	}
	
	public void setHadoopOutputFormat(org.apache.hadoop.mapreduce.OutputFormat<K,V> mapreduceOutputFormat) {
		this.mapreduceOutputFormat = mapreduceOutputFormat;
	}
	
	// --------------------------------------------------------------------------------------------
	//  OutputFormat
	// --------------------------------------------------------------------------------------------
	
	@Override
	public void configure(Configuration parameters) {
		// nothing to do
	}
	
	/**
	 * create the temporary output file for hadoop RecordWriter.
	 * @param taskNumber The number of the parallel instance.
	 * @param numTasks The number of parallel tasks.
	 * @throws IOException
	 */
	@Override
	public void open(int taskNumber, int numTasks) throws IOException {
		if (Integer.toString(taskNumber + 1).length() > 6) {
			throw new IOException("Task id too large.");
		}
		
		this.taskNumber = taskNumber+1;
		
		// for hadoop 2.2
		this.configuration.set("mapreduce.output.basename", "tmp");
		
		TaskAttemptID taskAttemptID = TaskAttemptID.forName("attempt__0000_r_" 
				+ String.format("%" + (6 - Integer.toString(taskNumber + 1).length()) + "s"," ").replace(" ", "0") 
				+ Integer.toString(taskNumber + 1) 
				+ "_0");
		
		this.configuration.set("mapred.task.id", taskAttemptID.toString());
		this.configuration.setInt("mapred.task.partition", taskNumber + 1);
		// for hadoop 2.2
		this.configuration.set("mapreduce.task.attempt.id", taskAttemptID.toString());
		this.configuration.setInt("mapreduce.task.partition", taskNumber + 1);
		
		try {
			this.context = HadoopUtils.instantiateTaskAttemptContext(this.configuration, taskAttemptID);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		System.out.println("HadoopOutputFormat: Write to " + this.configuration.get("mapred" +
				".output.dir"));
		this.fileOutputCommitter = new FileOutputCommitter(new Path(this.configuration.get("mapred.output.dir")), context);
		
		try {
			this.fileOutputCommitter.setupJob(HadoopUtils.instantiateJobContext(this.configuration, new JobID()));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		
		// compatible for hadoop 2.2.0, the temporary output directory is different from hadoop 1.2.1
		this.configuration.set("mapreduce.task.output.dir", this.fileOutputCommitter.getWorkPath().toString());
		
		try {
			this.recordWriter = this.mapreduceOutputFormat.getRecordWriter(this.context);
		} catch (InterruptedException e) {
			throw new IOException("Could not create RecordWriter.", e);
		}

		File dir = new File(this.configuration.get("mapred.output.dir"));

		if(dir.isDirectory()){
			File[] files = dir.listFiles();
			System.out.println(configuration.get("mapred.output.dir") + " contains the " +
					"following files.");
			for(File file: files){
				System.out.println(file.toURI());
			}
		}else if(dir.exists()){
			System.out.println(configuration.get("mapred.output.dir") + " is not a directory.");
		}else{
			System.out.println(configuration.get("mapred.output.dir") + " does not yet exists.");
		}
	}
	
	
	@Override
	public void writeRecord(Tuple2<K, V> record) throws IOException {
		try {
			this.recordWriter.write(record.f0, record.f1);
		} catch (InterruptedException e) {
			throw new IOException("Could not write Record.", e);
		}
	}
	
	/**
	 * commit the task by moving the output file out from the temporary directory.
	 * @throws IOException
	 */
	@Override
	public void close() throws IOException {
		System.out.println("HadoopOutputFormat: Close");
		try {
			this.recordWriter.close(this.context);
		} catch (InterruptedException e) {
			throw new IOException("Could not close RecordReader.", e);
		}
		
		if (this.fileOutputCommitter.needsTaskCommit(this.context)) {
			this.fileOutputCommitter.commitTask(this.context);
		}
		
		Path outputPath = new Path(this.configuration.get("mapred.output.dir"));

		File dir = new File(this.configuration.get("mapred.output.dir"));

		if(dir.isDirectory()){
			File[] files = dir.listFiles();
			System.out.println(configuration.get("mapred.output.dir") + " contains the " +
					"following files.");
			for(File file: files){
				System.out.println(file.toURI());
			}
		}else if(dir.exists()){
			System.out.println(configuration.get("mapred.output.dir") + " is not a directory.");
		}else{
			System.out.println(configuration.get("mapred.output.dir") + " does not yet exists.");
		}

		
		// rename tmp-file to final name
		FileSystem fs = FileSystem.get(outputPath.toUri(), this.configuration);
		
		String taskNumberStr = Integer.toString(this.taskNumber);
		String tmpFileTemplate = "tmp-r-00000";
		String tmpFile = tmpFileTemplate.substring(0,11-taskNumberStr.length())+taskNumberStr;
		
		if(fs.exists(new Path(outputPath.toString()+"/"+tmpFile))) {
			System.out.println("Rename file " +  new Path(outputPath.toString()+"/"+tmpFile) + " " +
					"to " + new Path(outputPath.toString()+"/"+taskNumberStr));
			fs.rename(new Path(outputPath.toString()+"/"+tmpFile), new Path(outputPath.toString()+"/"+taskNumberStr));
		}else{
			System.out.println("File does not exist?");
		}
	}
	
	@Override
	public void finalizeGlobal(int parallelism) throws IOException {

		System.out.println("Finalize HadoopOutputFormat.");
		JobContext jobContext;
		TaskAttemptContext taskContext;
		try {
			
			TaskAttemptID taskAttemptID = TaskAttemptID.forName("attempt__0000_r_" 
					+ String.format("%" + (6 - Integer.toString(1).length()) + "s"," ").replace(" ", "0") 
					+ Integer.toString(1) 
					+ "_0");
			
			jobContext = HadoopUtils.instantiateJobContext(this.configuration, new JobID());
			taskContext = HadoopUtils.instantiateTaskAttemptContext(this.configuration, taskAttemptID);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		this.fileOutputCommitter = new FileOutputCommitter(new Path(this.configuration.get("mapred.output.dir")), taskContext);
		
		// finalize HDFS output format
		this.fileOutputCommitter.commitJob(jobContext);
	}
	
	// --------------------------------------------------------------------------------------------
	//  Custom serialization methods
	// --------------------------------------------------------------------------------------------
	
	private void writeObject(ObjectOutputStream out) throws IOException {
		out.writeUTF(this.mapreduceOutputFormat.getClass().getName());
		this.configuration.write(out);
	}
	
	@SuppressWarnings("unchecked")
	private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
		String hadoopOutputFormatClassName = in.readUTF();
		
		org.apache.hadoop.conf.Configuration configuration = new org.apache.hadoop.conf.Configuration();
		configuration.readFields(in);
		
		if(this.configuration == null) {
			this.configuration = configuration;
		}
		
		try {
			this.mapreduceOutputFormat = (org.apache.hadoop.mapreduce.OutputFormat<K,V>) Class.forName(hadoopOutputFormatClassName, true, Thread.currentThread().getContextClassLoader()).newInstance();
		} catch (Exception e) {
			throw new RuntimeException("Unable to instantiate the hadoop output format", e);
		}
	}
}
