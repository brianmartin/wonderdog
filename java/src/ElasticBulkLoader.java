import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Random;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.util.GenericOptionsParser;
import org.apache.hadoop.conf.*;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.util.*;

import org.elasticsearch.node.Node;
import org.elasticsearch.client.Client;
import org.elasticsearch.node.NodeBuilder;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.Requests;
import org.elasticsearch.client.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.indices.IndexAlreadyExistsException;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.common.collect.Iterators;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.client.transport.TransportClient;

public class ElasticBulkLoader extends Configured implements Tool {

  public static class IndexMapper extends Mapper<LongWritable, Text, Text, Text> {
      
    private Node node;
    private Client client;
    private String indexName;
    private int bulkSize;
    private int keyField;
    private String objType;
    private String[] fieldNames;
    private volatile BulkRequestBuilder currentRequest;

    // Used for bookkeeping purposes
    private AtomicLong totalBulkTime  = new AtomicLong();
    private AtomicLong totalBulkItems = new AtomicLong();
    private Random     randgen        = new Random();
    private long       runStartTime   = System.currentTimeMillis();

    public void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {
        String[] fields = value.toString().split("\t");
        XContentBuilder builder = XContentFactory.jsonBuilder().startObject();
        for(int i = 0; i < fields.length; i++) {
            if (i < fieldNames.length) {
                builder.field(fieldNames[i], fields[i]);
            }
        }
        builder.endObject();
        currentRequest.add(Requests.indexRequest(indexName).type(objType).id(fields[keyField]).create(true).source(builder));
        processBulkIfNeeded();
        if (randgen.nextDouble() < 0.01) { context.write(new Text(fields[keyField]), new Text("Indexed") ); }
    }

    private void processBulkIfNeeded() {
      totalBulkItems.incrementAndGet();
      if (currentRequest.numberOfActions() >= bulkSize) {
        try {
          long startTime = System.currentTimeMillis();
          BulkResponse response = currentRequest.execute().actionGet();
          totalBulkTime.addAndGet(System.currentTimeMillis() - startTime);
          if (randgen.nextDouble() < 0.1) {
            System.out.println("Indexed [" + totalBulkItems.get() + "] in [" + (totalBulkTime.get()/1000) + "s] of indexing"+"[" + ((System.currentTimeMillis() - runStartTime)/1000) + "s] of wall clock"+" for ["+ (float)(1000.0*totalBulkItems.get())/(System.currentTimeMillis() - runStartTime) + "rec/s]");
          }
          if (response.hasFailures()) {
            System.out.println("failed to execute" + response.buildFailureMessage());
          }
        } catch (Exception e) {
          System.out.println("Bulk request failed: " + e.getMessage());
          throw new RuntimeException(e);
        }
        currentRequest = client.prepareBulk();
      }
    }

    protected void setup(org.apache.hadoop.mapreduce.Mapper.Context context) throws IOException, InterruptedException {
        Configuration conf = context.getConfiguration();
        this.indexName = conf.get("elasticsearch.index_name");
        this.bulkSize  = Integer.parseInt(conf.get("elasticsearch.bulk_size"));
        this.fieldNames = conf.get("elasticsearch.field_names").split(",");
        this.keyField   = Integer.parseInt(conf.get("elasticsearch.key_field"));
        this.objType    = conf.get("elasticsearch.object_type");
        System.setProperty("es.path.plugins",conf.get("elasticsearch.plugins_dir"));
        System.setProperty("es.config",conf.get("elasticsearch.config_yaml"));

        // start client type
        Integer transportClient = Integer.parseInt(conf.get("elasticsearch.transport_client"));
        if(transportClient == 1) {
            Integer initialPort = Integer.parseInt(conf.get("elasticsearch.initial_port"));
            String[] hosts      = conf.get("elasticsearch.hosts").split(",");
            start_transport_client(hosts, initialPort);
        } else {
            start_embedded_node();
        }
        initialize_index(indexName);
        currentRequest = client.prepareBulk();
    } 

    public void close() {
      client.close();
      System.out.println("Indexed [" + totalBulkItems.get() + "] in [" + totalBulkTime.get() + "ms]");
    }

    private void initialize_index(String indexName) {
        try {
            client.admin().indices().prepareCreate(indexName).execute().actionGet();
        } catch (Exception e) {
            if (ExceptionsHelper.unwrapCause(e) instanceof IndexAlreadyExistsException) {
                System.out.println("Index ["+indexName+"] already exists");
            }
        }
    }
    
    private void refresh_index(String indexName) {
        client.admin().indices().prepareRefresh(indexName).execute().actionGet();
    }

    private void start_embedded_node() {
        this.node   = NodeBuilder.nodeBuilder().client(true).node();
        this.client = node.client();
    }

    private void start_transport_client(String[] hosts, Integer initialPort) {
    //     this.client = new TransportClient();
    //     for(String host : hosts) {
    //         client.addTransportAddress(new InetSocketTransportAddress(host, initialPort));
    //         initialPort += 1;
    //     }
    }
    
  }

  public int run(String[] args) throws Exception {
    Job job                    = new Job(getConf());
    job.setJarByClass(ElasticBulkLoader.class);
    job.setJobName("ElasticBulkLoader");
    job.setMapperClass(IndexMapper.class);
    job.setNumReduceTasks(0);
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(Text.class);

    List<String> other_args = new ArrayList<String>();
    for (int i=0; i < args.length; ++i) {
      System.out.println(args[i]);
      other_args.add(args[i]);
    }

    FileInputFormat.setInputPaths(job, new Path(other_args.get(0)));

    // Used for logging?
    FileOutputFormat.setOutputPath(job, new Path(other_args.get(1)));
    
    try {
        job.waitForCompletion(true);
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
    return 0;
  }

  public static void main(String[] args) throws Exception {
    int res = ToolRunner.run(new Configuration(), new ElasticBulkLoader(), args);
    System.exit(res);
  }
}
