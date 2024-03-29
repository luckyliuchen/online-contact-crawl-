/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nutch.crawl;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

// Commons Logging imports
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.io.*;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.conf.*;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.util.*;

import org.apache.nutch.net.*;
import org.apache.nutch.scoring.ScoringFilterException;
import org.apache.nutch.scoring.ScoringFilters;
import org.apache.nutch.util.NutchConfiguration;
import org.apache.nutch.util.NutchJob;
import org.apache.nutch.util.TimingUtil;

/** This class takes a flat file of URLs and adds them to the of pages to be
 * crawled.  Useful for bootstrapping the system. 
 * The URL files contain one URL per line, optionally followed by custom metadata 
 * separated by tabs with the metadata key separated from the corresponding value by '='. <br>
 * Note that some metadata keys are reserved : <br>
 * - <i>nutch.score</i> : allows to set a custom score for a specific URL <br>
 * - <i>nutch.fetchInterval</i> : allows to set a custom fetch interval for a specific URL <br>
 * e.g. http://www.nutch.org/ \t nutch.score=10 \t nutch.fetchInterval=2592000 \t userType=open_source
 **/
public class Injector extends Configured implements Tool {
  public static final Logger LOG = LoggerFactory.getLogger(Injector.class);
  
  /** metadata key reserved for setting a custom score for a specific URL */
  public static String nutchScoreMDName = "nutch.score";
  /** metadata key reserved for setting a custom fetchInterval for a specific URL */
  public static String nutchFetchIntervalMDName = "nutch.fetchInterval";

  /** Normalize and filter injected urls. */
  public static class InjectMapper implements Mapper<WritableComparable, Text, Text, CrawlDatum> {
    private URLNormalizers urlNormalizers;
    private int interval;
    private float scoreInjected;
    private JobConf jobConf;
    private URLFilters filters;
    private ScoringFilters scfilters;
    private long curTime;

    public void configure(JobConf job) {
      this.jobConf = job;
      
      urlNormalizers = new URLNormalizers(job, URLNormalizers.SCOPE_INJECT);
      interval = jobConf.getInt("db.fetch.interval.default", 2592000);
      filters = new URLFilters(jobConf);
      scfilters = new ScoringFilters(jobConf);
      scoreInjected = jobConf.getFloat("db.score.injected", 1.0f);
      curTime = job.getLong("injector.current.time", System.currentTimeMillis());
    }

    public void close() {}

    public void map(WritableComparable key, Text value,
                    OutputCollector<Text, CrawlDatum> output, Reporter reporter)
      throws IOException {
      String url = value.toString();              // value is line of text
      //LOG.info("InjectMapper map url=" + url);

      if (url != null && url.trim().startsWith("#")) {
          /* Ignore line that start with # */
          return;
      }

      // if tabs : metadata that could be stored
      // must be name=value and separated by \t
      float customScore = -1f;
      int customInterval = interval;
      Map<String,String> metadata = new TreeMap<String,String>();
      if (url.indexOf("\t")!=-1){
    	  String[] splits = url.split("\t");
    	  url = splits[0];
    	  for (int s=1;s<splits.length;s++){
    		  // find separation between name and value
    		  int indexEquals = splits[s].indexOf("=");
    		  if (indexEquals==-1) {
    			  // skip anything without a =
    			  continue;		    
    		  }
    		  String metaname = splits[s].substring(0, indexEquals);
    		  String metavalue = splits[s].substring(indexEquals+1);
    		  if (metaname.equals(nutchScoreMDName)) {
    			  try {
    			  customScore = Float.parseFloat(metavalue);}
    			  catch (NumberFormatException nfe){}
    		  }
    		  else if (metaname.equals(nutchFetchIntervalMDName)) {
    			  try {
    				  customInterval = Integer.parseInt(metavalue);}
    			  catch (NumberFormatException nfe){}
    		  }
    		  else metadata.put(metaname,metavalue);
    	  }
      }
      try {
       url = urlNormalizers.normalize(url, URLNormalizers.SCOPE_INJECT);
       url = filters.filter(url);             // filter the url
     } catch (Exception e) {
        if (LOG.isWarnEnabled()) { LOG.warn("Skipping " +url+":"+e); }
        url = null;
      }
      if (url != null) {                          // if it passes
        value.set(url);                           // collect it
        CrawlDatum datum = new CrawlDatum(CrawlDatum.STATUS_INJECTED, customInterval);
        datum.setFetchTime(curTime);
        // now add the metadata
        Iterator<String> keysIter = metadata.keySet().iterator();
        while (keysIter.hasNext()){
        	String keymd = keysIter.next();
        	String valuemd = metadata.get(keymd);
            datum.getMetaData().put(new Text(keymd), new Text(valuemd));
        }
        if (customScore != -1) datum.setScore(customScore);
        else datum.setScore(scoreInjected);
        try {
        	scfilters.injectedScore(value, datum);
        } catch (ScoringFilterException e) {
        	if (LOG.isWarnEnabled()) {
        		LOG.warn("Cannot filter injected score for url " + url
        				+ ", using default (" + e.getMessage() + ")");
        	}
        }
        LOG.info("InjectMapper reduce value = " + value + " ; datum = " + datum.toString());
        output.collect(value, datum);
      }
    }
  }

  /** Combine multiple new entries for a url. */
  public static class InjectReducer implements Reducer<Text, CrawlDatum, Text, CrawlDatum> {
    public void configure(JobConf job) {
    	
        LOG.info("InjectReducer configure input path = " + FileInputFormat.getInputPaths(job)[0].toString() );
        LOG.info("InjectReducer configure out path = " + FileOutputFormat.getOutputPath(job).toString() );
    	
    }    
    public void close() {}

    private CrawlDatum old = new CrawlDatum();
    private CrawlDatum injected = new CrawlDatum();
    
    public void reduce(Text key, Iterator<CrawlDatum> values,
                       OutputCollector<Text, CrawlDatum> output, Reporter reporter)
      throws IOException {
      boolean oldSet = false;
      while (values.hasNext()) {
    	  
        CrawlDatum val = values.next();
        LOG.info("InjectReducer reduce start key = " + key + ";  val = " + val.toString());
         
        if (val.getStatus() == CrawlDatum.STATUS_INJECTED) {
          injected.set(val);
          injected.setStatus(CrawlDatum.STATUS_DB_UNFETCHED);
        } else {
          old.set(val);
          oldSet = true;
        }
      }
      CrawlDatum res = null;
      if (oldSet) res = old; // don't overwrite existing value
      else res = injected;
      LOG.info("InjectReducer reduce key = " + key + " ; res = " + res.toString());

      output.collect(key, res);
    }
  }

  public Injector() {}
  
  public Injector(Configuration conf) {
    setConf(conf);
  }
  
  public void inject(Path crawlDb, Path urlDir) throws IOException {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    long start = System.currentTimeMillis();
    if (LOG.isInfoEnabled()) {
      LOG.info("Injector: starting at " + sdf.format(start));
      LOG.info("Injector: crawlDb: " + crawlDb);
      LOG.info("Injector: urlDir: " + urlDir);
    }

    Path tempDir =
      new Path(getConf().get("mapred.temp.dir", ".") +
               "/inject-temp-"+
               Integer.toString(new Random().nextInt(Integer.MAX_VALUE)));

    // map text input file to a <url,CrawlDatum> file
    if (LOG.isInfoEnabled()) {
      LOG.info("Injector: Converting injected urls to crawl db entries tempDir = " + tempDir.toString());
    }
    JobConf sortJob = new NutchJob(getConf());
    sortJob.setJobName("inject " + urlDir);
    FileInputFormat.addInputPath(sortJob, urlDir);
    sortJob.setMapperClass(InjectMapper.class);

    FileOutputFormat.setOutputPath(sortJob, tempDir);
    sortJob.setOutputFormat(SequenceFileOutputFormat.class);
    sortJob.setOutputKeyClass(Text.class);
    sortJob.setOutputValueClass(CrawlDatum.class);
    sortJob.setLong("injector.current.time", System.currentTimeMillis());
    JobClient.runJob(sortJob);
    

    // merge with existing crawl db
    if (LOG.isInfoEnabled()) {
      LOG.info("Injector: Merging injected urls into crawl db. tempDir = " + tempDir );
    }
    JobConf mergeJob = CrawlDb.createJob(getConf(), crawlDb);
    FileInputFormat.addInputPath(mergeJob, tempDir);
    mergeJob.setReducerClass(InjectReducer.class);
    JobClient.runJob(mergeJob);
    LOG.info("Injector: Merging injected urls into crawl db CrawlDb start.");
    
    CrawlDb.install(mergeJob, crawlDb);

    // clean up
    FileSystem fs = FileSystem.get(getConf());
    fs.delete(tempDir, true);

    long end = System.currentTimeMillis();
    LOG.info("Injector: finished at " + sdf.format(end) + ", elapsed: " + TimingUtil.elapsedTime(start, end));
    
  }

  public static void main(String[] args) throws Exception {
    int res = ToolRunner.run(NutchConfiguration.create(), new Injector(), args);
    System.exit(res);
  }
  
  public int run(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("Usage: Injector <crawldb> <url_dir>");
      return -1;
    }
    try {
      inject(new Path(args[0]), new Path(args[1]));
      return 0;
    } catch (Exception e) {
      LOG.error("Injector: " + StringUtils.stringifyException(e));
      return -1;
    }
  }

}
