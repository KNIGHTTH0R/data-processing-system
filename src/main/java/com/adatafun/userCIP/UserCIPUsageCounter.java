package com.adatafun.userCIP;

import com.adatafun.conf.ESMysqlSpark;
import com.adatafun.model.RestaurantUser;
import org.apache.pig.builtin.mock.Storage;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.sql.*;
import org.elasticsearch.spark.sql.EsSparkSQL;
import scala.Tuple2;
import scala.collection.JavaConversions;
import scala.collection.Seq;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Created by yanggf on 2017/10/24.
 */
public class UserCIPUsageCounter {
    public static void main(String[] args){
        SparkSession spark = ESMysqlSpark.getSession();
        Properties prop = ESMysqlSpark.getMysqlConf3();
        try{
            String table = "tb_order_cip";
            Dataset orderDS = spark.read().jdbc(prop.getProperty("url"),table,prop);
            Dataset tbOrderDS = spark.read().jdbc(prop.getProperty("url"),"tb_order",prop);
            Dataset usageDS = spark.read().jdbc(prop.getProperty("url"),"tb_order_cip_record",prop);
            Dataset tbOrderRenameDS = tbOrderDS.withColumnRenamed("order_no", "order_no1");
            Dataset togetherDS = orderDS.join(tbOrderRenameDS, orderDS.col("order_no").equalTo(tbOrderRenameDS.col("order_no1")),"left_outer");
            Dataset allDS = usageDS.join(togetherDS, usageDS.col("order_no").equalTo(togetherDS.col("order_no")),"left_outer");

            List<Column> listColumns = new ArrayList<Column>();
            listColumns.add(tbOrderDS.col("user_id"));//用户id
            listColumns.add(orderDS.col("pro_code"));//餐馆code

            Seq<Column> seqCol = JavaConversions.asScalaBuffer(listColumns).toSeq();
            Dataset resultDS = allDS.select(seqCol);//裁剪后的数据
            Dataset resultNull = resultDS.na().drop();//null值数据删除掉
            JavaRDD<Row> rowRDD = resultNull.toJavaRDD();

            JavaPairRDD<Tuple2<String, String>, Integer> CIPPairRDD = rowRDD.mapToPair(new PairFunction<Row, Tuple2<String, String>, Integer>() {
                public Tuple2<Tuple2<String, String>, Integer> call(Row row) throws Exception {
                    String userId = String.valueOf(row.getAs(0));
                    String CIPCode = row.getAs(1);
                    Tuple2<String, String> tpl2 = new Tuple2<String,String>(userId, CIPCode);
                    return new Tuple2<Tuple2<String, String>, Integer>(tpl2, 1);
                }
            });

            JavaPairRDD< Tuple2<String,String>,Integer > numRDD = CIPPairRDD.reduceByKey(new Function2<Integer, Integer, Integer>() {
                public Integer call(Integer integer, Integer integer2) throws Exception {
                    return integer + integer2;
                }
            });
            JavaRDD<RestaurantUser> restRDD = numRDD.map(new Function<Tuple2<Tuple2<String, String>, Integer>, RestaurantUser>() {
                public RestaurantUser call(Tuple2<Tuple2<String, String>, Integer> tuple2IntegerTuple2) throws Exception {
                    RestaurantUser rest = new RestaurantUser();
                    rest.setId(tuple2IntegerTuple2._1()._1() + tuple2IntegerTuple2._1()._2());
                    rest.setUserId(tuple2IntegerTuple2._1()._1());
                    rest.setRestaurantCode(tuple2IntegerTuple2._1()._2());
                    rest.setUsageCounter(tuple2IntegerTuple2._2());
                    return rest;
                }
            });
            SQLContext context = new SQLContext(spark);
            Dataset ds = context.createDataFrame(restRDD, RestaurantUser.class);
//            ds.toJavaRDD().saveAsTextFile("f:/CIPIPIPIPIPI");
            EsSparkSQL.saveToEs(ds,"user/userCIP");
        } catch (Exception e){
            e.printStackTrace();
        }
    }
}
