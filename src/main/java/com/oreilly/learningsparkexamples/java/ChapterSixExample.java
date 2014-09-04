/**
 * Illustrates using counters and broadcast variables for chapter 6
 */
package com.oreilly.learningsparkexamples.java;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.regex.*;
import java.util.Scanner;
import java.util.Iterator;
import java.io.File;
import java.io.FileNotFoundException;

import scala.Tuple2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;

import org.apache.commons.lang.StringUtils;

import org.apache.spark.Accumulator;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaDoubleRDD;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.DoubleFunction;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.SparkFiles;
import org.apache.spark.util.StatCounter;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;

public class ChapterSixExample {
  public static class SumInts implements Function2<Integer, Integer, Integer> {
    public Integer call(Integer x, Integer y) {
      return x + y;
    }
  }

  public static class VerifyQSOs implements Function<QSO[], QSO[]> {
    public QSO[] call(QSO[] input) {
      ArrayList<QSO> res = new ArrayList<QSO>();
      if (input != null) {
        for (QSO call: input) {
          if (call != null && call.mylat != null && call.mylong != null
              && call.contactlat != null && call.contactlong != null) {
            res.add(call);
          }
        }
      }
      return res.toArray(new QSO[0]);
    }
  }

  public static void main(String[] args) throws Exception {

		if (args.length != 4) {
      throw new Exception("Usage AccumulatorExample sparkMaster inputFile outDirectory");
		}
    String sparkMaster = args[0];
    String inputFile = args[1];
    String inputFile2 = args[2];
    String outputDir = args[3];

    JavaSparkContext sc = new JavaSparkContext(
      sparkMaster, "ChapterSixExample", System.getenv("SPARK_HOME"), System.getenv("JARS"));
    JavaRDD<String> rdd = sc.textFile(inputFile);
    // Count the number of lines with KK6JKQ
    final Accumulator<Integer> count = sc.accumulator(0);
    rdd.foreach(new VoidFunction<String>(){ public void call(String line) {
          if (line.contains("KK6JKQ")) {
            count.add(1);
          }
        }});
    System.out.println("Lines with 'KK6JKQ': " + count.value());
    // Create Accumulators initialized at 0
    final Accumulator<Integer> blankLines = sc.accumulator(0);
    JavaRDD<String> callSigns = rdd.flatMap(
      new FlatMapFunction<String, String>() { public Iterable<String> call(String line) {
          if (line.equals("")) {
            blankLines.add(1);
          }
          return Arrays.asList(line.split(" "));
        }});
    callSigns.saveAsTextFile(outputDir + "/callsigns");
    System.out.println("Blank lines: "+ blankLines.value());
    // Start validating the call signs
    final Accumulator<Integer> validSignCount = sc.accumulator(0);
    final Accumulator<Integer> invalidSignCount = sc.accumulator(0);
    JavaRDD<String> validCallSigns = callSigns.filter(
      new Function<String, Boolean>(){ public Boolean call(String callSign) {
          Pattern p = Pattern.compile("\\A\\d?\\p{Alpha}{1,2}\\d{1,4}\\p{Alpha}{1,3}\\Z");
          Matcher m = p.matcher(callSign);
          boolean b = m.matches();
          if (b) {
            validSignCount.add(1);
          } else {
            invalidSignCount.add(1);
          }
          return b;
        }
      });
    JavaPairRDD<String, Integer> contactCounts = validCallSigns.mapToPair(
      new PairFunction<String, String, Integer>() {
        public Tuple2<String, Integer> call(String callSign) {
          return new Tuple2(callSign, 1);
        }}).reduceByKey(new Function2<Integer, Integer, Integer>() {
            public Integer call(Integer x, Integer y) {
              return x + y;
            }});
    // Force evaluation so the counters are populated
    contactCounts.count();
    if (invalidSignCount.value() < 0.1 * validSignCount.value()) {
      contactCounts.saveAsTextFile(outputDir + "/contactCount");
    } else {
      System.out.println("Too many errors " + invalidSignCount.value() +
                         " for " + validSignCount.value());
      System.exit(1);
    }
    // Read in the call sign table
    // Lookup the countries for each call sign in the
    // contactCounts RDD
    final Broadcast<String[]> signPrefixes = sc.broadcast(loadCallSignTable());
    JavaPairRDD<String, Integer> countryContactCounts = contactCounts.mapToPair(
      new PairFunction<Tuple2<String, Integer>, String, Integer> (){
        public Tuple2<String, Integer> call(Tuple2<String, Integer> callSignCount) {
          String sign = callSignCount._1();
          String[] callSignInfo = signPrefixes.value();
          String country = lookupCountry(sign, callSignInfo);
          return new Tuple2(country, callSignCount._2());
        }}).reduceByKey(new SumInts());
    countryContactCounts.saveAsTextFile(outputDir + "/countries.txt");
    // use mapPartitions to re-use setup work
    JavaPairRDD<String, QSO[]> contactsContactLists = validCallSigns.mapPartitionsToPair(
      new PairFlatMapFunction<Iterator<String>, String, QSO[]>() {
        public Iterable<Tuple2<String, QSO[]>> call(Iterator<String> input) {
          ArrayList<Tuple2<String, QSO[]>> callsignQsos =
            new ArrayList<Tuple2<String, QSO[]>>();
          ArrayList<Tuple2<String, ContentExchange>> ccea =
            new ArrayList<Tuple2<String, ContentExchange>>();
          ObjectMapper mapper = new ObjectMapper();
          mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
          HttpClient client = new HttpClient();
          try {
            client.start();
            while (input.hasNext()) {
              ContentExchange exchange = new ContentExchange(true);
              String sign = input.next();
              exchange.setURL("http://new73s.herokuapp.com/qsos/" + input.next() + ".json");
              client.send(exchange);
              ccea.add(new Tuple2(sign, exchange));
            }
            for (Tuple2<String, ContentExchange> signExchange : ccea) {
              String sign = signExchange._1();
              ContentExchange exchange = signExchange._2();
              exchange.waitForDone();
              String responseJson = exchange.getResponseContent();
              QSO[] qsos = mapper.readValue(responseJson, QSO[].class);
              callsignQsos.add(new Tuple2(sign, qsos));
            }
          } catch (Exception e) {
          }
          return callsignQsos;
        }});
    System.out.println(StringUtils.join(contactsContactLists.collect(), ","));
    // Computer the distance of each call using an external R program
    // adds our script to a list of files for each node to download with this job
    String distScript = "./src/R/finddistance.R";
    String distScriptName = "finddistance.R";
    sc.addFile(distScript);
    JavaRDD<String> pipeInputs = contactsContactLists.values().map(new VerifyQSOs()).flatMap(
      new FlatMapFunction<QSO[], String>() { public Iterable<String> call(QSO[] calls) {
          ArrayList<String> latLons = new ArrayList<String>();
          for (QSO call: calls) {
            latLons.add(call.mylat + "," + call.mylong +
                        "," + call.contactlat + "," + call.contactlong);
          }
          return latLons;
        }
      });
    JavaRDD<String> distance = pipeInputs.pipe(SparkFiles.get(distScriptName));
    // First we need to convert our RDD of String to a DoubleRDD so we can
    // access the stats function
    JavaDoubleRDD distanceDouble = distance.mapToDouble(new DoubleFunction<String>() {
        public double call(String value) {
          return Double.parseDouble(value);
        }});
    final StatCounter stats = distanceDouble.stats();
    final Double stddev = stats.stdev();
    final Double mean = stats.mean();
    JavaDoubleRDD reasonableDistance =
      distanceDouble.filter(new Function<Double, Boolean>() {
        public Boolean call(Double x) {
          return (Math.abs(x-mean) < 3 * stddev);}});
    System.out.println(StringUtils.join(reasonableDistance.collect(), ","));
  }

  static String[] loadCallSignTable() throws FileNotFoundException {
    Scanner callSignTbl = new Scanner(new File("./files/callsign_tbl_sorted"));
    ArrayList<String> callSignList = new ArrayList<String>();
    while (callSignTbl.hasNextLine()) {
      callSignList.add(callSignTbl.nextLine());
    }
    return callSignList.toArray(new String[0]);
  }

  static String lookupCountry(String callSign, String[] table) {
      Integer pos = java.util.Arrays.binarySearch(table, callSign);
      if (pos < 0) {
        pos = -pos-1;
      }
      return table[pos].split(",")[1];
  }
}
