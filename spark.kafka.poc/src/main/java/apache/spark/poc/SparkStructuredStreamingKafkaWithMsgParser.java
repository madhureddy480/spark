package apache.spark.poc;

import org.apache.spark.TaskContext;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.streaming.StreamingQueryException;
import org.apache.spark.sql.streaming.Trigger;

import com.fasterxml.jackson.databind.ObjectMapper;

import apache.spark.poc.config.Configuration;
import apache.spark.poc.entity.Message;
import apache.spark.poc.utils.LocalToHDFSCopy;

class CopyStatus {
  String hdfsLocation;
  Boolean status;

  public String getHdfsLocation() {
    return hdfsLocation;
  }

  public void setHdfsLocation(String hdfsLocation) {
    this.hdfsLocation = hdfsLocation;
  }

  public Boolean getStatus() {
    return status;
  }

  public void setStatus(Boolean status) {
    this.status = status;
  }
  
  public CopyStatus(String hdfsLocation, boolean status) {
    this.hdfsLocation = hdfsLocation;
    this.status = status;
  }
}


public class SparkStructuredStreamingKafkaWithMsgParser {


  public static void main(String[] args) throws StreamingQueryException {

    SparkSession spark = SparkSession.builder()
        .appName("JavaStructuredKafkaWordCount").master("local[4]")
        .config("spark.executor.memory", "2g").getOrCreate();

//    StructType schemaType =
//        new StructType().add("taskId", "long").add("fileName", "string")
//            .add("skipHeader", "boolean").add("hdfsLocation", "string");

    // Create DataSet representing the stream of input lines from kafka
    Dataset<String> kafkaValues = spark.readStream().format("kafka")
        // .option("kafka.group.id", "sparkStream-gid")
        .option("spark.streaming.receiver.writeAheadLog.enable", true)
        .option("kafka.bootstrap.servers", Configuration.KAFKA_BROKER)
        .option("subscribe", Configuration.KAFKA_TOPIC)
        .option("fetchOffset.retryIntervalMs", 100)
        .option("checkpointLocation", "file:///tmp/checkpoint").load()
        .selectExpr("CAST(value AS STRING)").as(Encoders.STRING());

//    StructType schema = kafkaValues.schema();

//    Dataset<String> locationRows = kafkaValues.map(x -> {
//      ObjectMapper mapper = new ObjectMapper();
//      Message m = mapper.readValue(x.getBytes(), Message.class);
//      return m.getHdfsLocation();
//    }, Encoders.STRING());
    
    // User HDFS's copy from local method
    Dataset<String> copyStatusRows = kafkaValues.map(x -> {
      ObjectMapper mapper = new ObjectMapper();
      Message m = mapper.readValue(x.getBytes(), Message.class);
      String source = m.getFileName();
      String dest = m.getHdfsLocation();
      boolean copyStatus = LocalToHDFSCopy.copyToHDFS(source, dest);
      // return new CopyStatus(m.getHdfsLocation(), copyStatus);     
      
      TaskContext tc = TaskContext.get();
      System.out.println("TaskAttempt Id : " + tc.taskAttemptId());
      System.out.println("TaskPartition Id " + TaskContext.getPartitionId() );
      
      return m.getHdfsLocation();
    }, Encoders.STRING());

   
//    StreamingQuery query = copyStatusRows.writeStream().outputMode("append")
//        // .option("checkpointLocation", "file:///tmp/checkpoint1")
//        .format("console").start();

    StreamingQuery query = copyStatusRows.writeStream()
        .outputMode("append")
        .format("parquet")
        .option("path", "/tmp/parquetFile")
        .trigger(Trigger.ProcessingTime(1000 * 10 ))
        .option("checkpointLocation", "file:///tmp/checkpoint2")
        .start();
    
    
    // Start running the query that prints the running counts to the console
//    StreamingQuery query = locationRows.writeStream().outputMode("append")
//        .option("checkpointLocation", "file:///tmp/checkpoint1")
//        .format("console").start();

    // Start a monitoring thread over query
    new Thread(() -> {
      try {
        while (true) {
          System.out.println("Last Progress " + query.lastProgress());
          Thread.sleep(5000);
        }
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }).start();

    System.out.println("Spark writing to console :" + query.status());
    
    query.awaitTermination();
  }
}
