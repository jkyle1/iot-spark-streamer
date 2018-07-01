package main.scala.com.challenge.app.processor

import java.text.SimpleDateFormat
import java.util.Calendar

import com.typesafe.scalalogging.Logger
import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.client.Put
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.mapred.TableOutputFormat
import org.apache.hadoop.hbase.util.Bytes
import org.apache.hadoop.mapred.JobConf
import org.apache.spark.SparkConf
import org.apache.spark.streaming.kafka010.{ConsumerStrategies, KafkaUtils, LocationStrategies}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.slf4j.LoggerFactory


object KafkaSparkStreamer {

  final val localLogger = Logger(LoggerFactory.getLogger("KafkaSparkStreamer"))
  final val tableName = "iot-stream"
  final val dataBytes = Bytes.toBytes("data")
  final val colIdBytes = Bytes.toBytes("deviceId")
  final val colTempBytes = Bytes.toBytes("temperature")
  final val colLatBytes = Bytes.toBytes("latitude")
  final val colLonBytes = Bytes.toBytes("longitude")
  final val colTimeBytes = Bytes.toBytes("time")
  final val dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX”")

  // schema
  case class IoTDeviceMessage(deviceId: String, temperature: Int, latitude: Double, longitude: Double, time: Long)

  object IoTDeviceMessage extends Serializable {
    // function to parse line of data into IoTDeviceMessage class
    def parseIoT(str: String): IoTDeviceMessage = {
      val p = str.split(",")
      IoTDeviceMessage(p(0), p(1).toInt, p(2).toDouble, p(3).toDouble, p(4).toLong)
    }

    //  Convert IoTDeviceMessage to HBase put object
    def convertToPut(message: IoTDeviceMessage): (ImmutableBytesWritable, Put) = {
      //    val time = message.date + " " + message.time
      //    // create a composite row key: sensorid_date time
      val rowkey = message.deviceId + "_" + Calendar.getInstance()
      val put = new Put(Bytes.toBytes(rowkey))
      // add to column family data, column  data values to put object
      //put.addImmutable(dataBytes, dataBytes, message)
      put.addImmutable(dataBytes, colIdBytes, Bytes.toBytes(message.deviceId))
      put.addImmutable(dataBytes, colTempBytes, Bytes.toBytes(message.temperature))
      put.addImmutable(dataBytes, colLatBytes, Bytes.toBytes(message.latitude))
      put.addImmutable(dataBytes, colLonBytes, Bytes.toBytes(message.longitude))
      put.addImmutable(dataBytes, colTimeBytes, Bytes.toBytes(dateTimeFormat.format(message.time)))
      return (new ImmutableBytesWritable(Bytes.toBytes(rowkey)), put)
    }
  }


  def main(args: Array[String]) {

    // set up HBase Table configuration
    val conf = HBaseConfiguration.create()
    conf.set(TableOutputFormat.OUTPUT_TABLE, tableName)
    val jobConfig: JobConf = new JobConf(conf, this.getClass)
    //jobConfig.set("mapreduce.output.fileoutputformat.outputdir", "/tmp/hbase")
    jobConfig.setOutputFormat(classOf[TableOutputFormat])
    jobConfig.set(TableOutputFormat.OUTPUT_TABLE, tableName)

    //Kafka configuration
    val kafkaTopic = "iot-device-event"
    val kafkaBroker = "127.0.01:9092"
    val topics: Set[String] = kafkaTopic.split(",").map(_.trim).toSet
    val kafkaParams = Map[String, String](
      "bootstrap.servers" -> "localhost:9092",
      "group.id" -> "use_a_separate_group_id_for_each_stream",
      "auto.offset.reset" -> "smallest")

    //Spark configuration and Streaming Context
    val sparkCfg = new SparkConf().setAppName("IoTDeviceStreamer").setMaster("local[2]")
    val ssc = new StreamingContext(sparkCfg, Seconds(2))
    ssc.checkpoint("/tmp")

    localLogger.info(s"connecting to brokers: $kafkaBroker")
    localLogger.info(s"kafkaParams: $kafkaParams")
    localLogger.info(s"topics: $topics")

    val iotStream = KafkaUtils.createDirectStream[String, String](ssc, LocationStrategies.PreferConsistent,
      ConsumerStrategies.Subscribe[String, String](topics, kafkaParams))

    val iotParsed = iotStream.map(record => (record.value)).map(IoTDeviceMessage.parseIoT)

    iotParsed.foreachRDD{ rdd => rdd.map(IoTDeviceMessage.convertToPut).saveAsNewAPIHadoopDataset(jobConfig) }
    //TODO work in progress

      // convert iot message to put object and write to HBase table iot-stream
    //iotStream.foreachRDD { rdd =>
    // rdd.map(IoTDeviceMessage.parseIoT()).saveAsNewAPIHadoopDataset(jobConfig)
    //}



    //Kick off
    ssc.start()

    ssc.awaitTermination()

    ssc.stop()
  }

}


