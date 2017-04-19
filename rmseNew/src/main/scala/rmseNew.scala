//import com.amazonaws.auth.{AWSCredentials, BasicAWSCredentials}
//import com.amazonaws.ClientConfiguration
//import com.amazonaws.services.s3.AmazonS3Client
//import com.amazonaws.services.s3.transfer.TransferManager
import java.io.File

import org.apache.spark._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd._
import org.apache.spark.SparkContext._
import org.apache.spark.storage.StorageLevel.MEMORY_AND_DISK_SER
//import org.apache.spark.sql.types.{StructType, StructField, IntegerType}

import scala.io.Source

object RmseNew {
  /*  Spark Application used for grid searching Like2Vec by generating either the naive or weighted RMSE for different
      sets of embeddings and can be (and has been) deployed as an AWS EMR step
      Requires passing the location of a s3a bucket that contains the relevant data including:
          - folder containing all text files which have the hyperparameter data used for the embeddings
          - folder containing all text files which have the embedding data
          - file containing the training data
          - file containing the test data
          - text file called confRmse.txt containing the following information:
              accessKey: String; AWS account access key
              secretKey: String; AWS account secret key
              trainDir: String; location file in S3 where training data is contained
              testDir: String; location file in S3 where test data is contained
              hyperParamDir: String; location of folder in S3 containing your hyperparameter text files
              hyperParamDirNum: Integer; number of hyperparameter files
              embeddingDir: String; location of folder in S3 containing your embeddings
              embeddingDirNum: Integer; number of embedding files
              llrSuffix: String; contains the unique identifier for the given set of embeddings
              numMovies: Integer; number of movies to be used to generate the predicted rating
              typeAvg: Integer; type of average to use for predicting ratings if typeAvg == 0 weighted average will be
                                performed otherwise naive average will be performed
              outputDir: String; location want

  */

  def getConfMap(dir: String)(implicit sc: SparkContext): scala.collection.Map[String,String] = {
    /* generate the map of hyperparameters provided by the user to be used in the program

       Parameters
       ----------
       dir: String; location of hyperparameter text file in s3

       sc: SparkContext; implicit parameter, SparkContext used to read text file

       Return
       ------
       scala.collection.Map[String,String]; Map where keys are the hyperparameter name and values are the
                                            hyperparameter values

     */
    sc.textFile(dir).map{line =>
      val Array(key, value) = line.split(";")
      (key, value)
    }.collectAsMap()
  }

  def getTrainOrTest(fileName: String)(implicit sc: SparkContext): RDD[(Int, Int, Double)] = {
    /* load train set or test set into RDD and format

       Parameters
       ----------
       fileName: String; location of either the test or train data in s3

       sc: SparkContext; implicit parameter, SparkContext used to read text file

       Return
       ------
       RDD[(Int, Int, Double)]; RDD containing either train or test data where each row is an observation, the first
                                column is user id, second column is movie id, third column is the rating for the given
                                movie provided by the given user

     */
    sc.textFile(fileName).map{
      line =>
        try {
          val Array(user, movie, rating) = line.split(",")
        (user.toInt, movie.toInt, rating.toDouble)
      }
      catch {
      case e: Exception => (0, 0, 0)
    }
      //    }

      //    sc.textFile(fileName).map(f = _.split(',') match {
      //      case Array(user, movie, rating) =>
      //        (user.toInt, movie.toInt, rating.toDouble)
      //    })
    }
  }

  def getParamMap(hyperParamDirs: List[String])(implicit sc: SparkContext): Map[String, Array[String]] = {
    /* convert all files with hyperparameter values into a map where key is the index of the run
       and values are the hyperparameter values

       Parameters
       ----------
       hyperParamDirs:List[String]; list of locations of text file containing hyperparameters used to train the embeddings

       sc: SparkContext; implicit parameter, SparkContext used to read text file

       Return
       ------
       Map[String, Array[String]]; map of hyperparameters for embeddings key is the number of the embedding run and value
                                   is array of hyperparameters used for that embedding run

    */
    hyperParamDirs.flatMap{fileName =>
      sc.textFile(fileName).map(i => i.replace("(","").replace(")","").split(",")).map(i=> (i(0),i.drop(1))).collect()}.toMap
  }

  def getEmbedHyperParam(hyperParamMap: Map[String, Array[String]])(sampleFile: String)(implicit sc: SparkContext):
  (Array[String],RDD[(Int,Array[Double])])={
    /* load and format embeddings into map, keys are movie indicies and values are embeddings
       the hyperparameters for the given embeddings are also returned

       Parameters
       ----------
       hyperParamMap: Map[String, Array[String]]; map of hyperparameters for embeddings key is the number of the
                                                  embedding run and value is array of hyperparameters used for that
                                                  embedding run

       sampleFile: String; location of a set of embeddings in s3

       sc: SparkContext; implicit parameter, SparkContext used to read text file

       Return
       ------
       (hyperParamMap(str),embeddings): (Array[String],RDD[(Int,Array[Double])]); tuple where first element is an array
                                                                                  of hyperparameters used for that
                                                                                  embedding run, second value is RDD
                                                                                  containing RDD of embeddings for a
                                                                                  specific run where the first value
                                                                                  is the movie id, the second value
                                                                                  is an Array containing the given movie's
                                                                                  item embedding

     */
    val embeddings = sc.textFile(sampleFile).map{ line =>
//      val fields = line.split(" ")
      val fields = line.split(" ")
//      val movie_id = (fields.head.toInt +1)
      val movie_id = (fields.head.toInt)
      val vector = fields.tail.map(_.toDouble).toArray
      (movie_id, vector)
//      val fields = line.replace("List","").replace("(","").replace(")","").split(",")
//      val movie_id = (fields.head.toInt +1)
//      val vector = fields.tail.map(_.toDouble).toArray
//      (movie_id, vector)
    }
//    val str = sampleFile.split("/").last.split("_").last.split(".txt")(0)
//    val str = sampleFile.split("/").last
    val str = "0"
    (hyperParamMap(str),embeddings)

  }

  def rmseCalculate(getEmbeddings: String => (Array[String], RDD[(Int,Array[Double])]), numMovies: Int,
                    trainData: List[RDD[(Int, List[(Int, Double)])]], testData: RDD[(Int, List[(Int, Double)])],
                    typeAvg: Int, outputDir: String)(embedFile: String)(implicit sc: SparkContext): Unit = {
    /* calculate either weighted or naive RMSE for given set of embeddings along with associated hyperparameters

       Parameters
       ----------
       getEmbeddings: String => (Array[String], RDD[(Int,Array[Double])]); partially applied function that takes the
                                                                           location of a set of embeddings in s3 and
                                                                           generates a tuple hyperparameters used for that
                                                                           embedding run, second value is RDD
                                                                           containing RDD of embeddings for a
                                                                           specific run where the first value
                                                                           is the movie id, the second value
                                                                           is an Array containing the given movie's
                                                                           item embedding
       numMovies: Int; number movies to use to calculate the predicted rating
       trainData: List[RDD[(Int, List[(Int, Double)])]]; list of RDDs containing all training data where each row is an
                                                         observation contains user id, and the second column is a list
                                                         of tuples of movie id, and rating for the given movie provided
                                                         by the given user
       testData: RDD[(Int, List[(Int, Double)])]; RDD containing all test data where each row is an
                                                  observation contains user id, and the second column is a list
                                                  of tuples of movie id, and rating for the given movie provided
                                                  by the given user
       typeAvg: Int; the type of average to use when calculating the predicted rating
       outputDir: String; location in s3 where you want the output data written

       embedFile: String; location of a set of embeddings in s3

       sc: SparkContext; implicit parameter, SparkContext used to write results to text file

       Return
       ------
       Unit; function returns nothing, the resulting RMSE of the function is written to the location in s3 you specified

     */

    def cosineSimilarity(arr1: Array[Double], arr2: Array[Double]): Double ={
      /* calculate cosine similarity for two given embeddings

         Parameters
         ----------
         arr1: Array[Double]; first embedding you want to calculate cosine similarity for
         arr2: Array[Double]; second embedding you want to calculate cosine similarity for

         Return
         ------
         Double; cosine similarity between arr1 and arr2 (value is between [-1.0, 1.0])

       */
      def dotProduct(x: Array[Double], y: Array[Double]): Double = {
        /* calculate dot product for two embeddings

           Parameters
           ----------
           x: Array[Double]; first embedding you want to use to calculate the dot product for
           y: Array[Double]; second embedding you want to use to calculate the dot product for

           Return
           ------
           Double; dot product between x and y

         */
        x.zipWithIndex.foldLeft(0.0)((acc,tup) => acc+tup._1*y(tup._2))
      }
      def norm(x: Array[Double]): Double ={
        /* calculate L2 norm for given embeddings

           Parameters
           ----------
           x: Array[Double]; embdedding you would like to calculate the L2 norm for

           Return
           ------
           Double; L2 norm for the given embedding

         */
        math.sqrt(dotProduct(x,x))
      }

      val numerator = dotProduct(arr1, arr2)
      val denom1 = norm(arr1)
      val denom2 = norm(arr2)
      val denominator = denom1*denom2

      (numerator/denominator)
    }

    def weightedAverage(topItems: List[(Double,Double)]): Double = {
      /* calculate weighted average for given weights and ratings returns predicted rating

         Parameters
         ----------
         topItems: List[(Double,Double)]; list of length numWalks containing tuples where the first value is the
                                          cosine similarity between the given training item's embedding and the test
                                          item's embedding and the second value is the rating for the given training item

         Return
         ------
         Double; predicted rating for the test item

       */
      val (num, denom) = topItems.foldLeft((0.0,0.0)){(acc,entry) =>
        val expWeight = math.exp(entry._1)
        val sumWeightedRatings = acc._1 + (expWeight * entry._2)
        val sumOfDistances = acc._2 + expWeight
        (sumWeightedRatings, sumOfDistances)
      }
      num/denom
    }

    def naiveAverage(topItems: List[(Double, Double)]): Double = {
      /* calculate naive average for given weights and ratings(weights ignored) returns predicted rating

         Parameters
         ----------
         topItems: List[(Double,Double)]; list of length numWalks containing tuples where the first value is the
                                          cosine similarity between the given training item's embedding and the test
                                          item's embedding and the second value is the rating for the given training item

         Return
         ------
         Double; predicted rating for the test item

       */
      topItems.map(_._2).sum/numMovies
    }

    val (Array(embedingSize, walkLength, numWalks), embeddingRDD) = getEmbeddings(embedFile)
    val embeddings = sc.broadcast(embeddingRDD.collectAsMap())

    def calcErr(cntr: Integer, predictions: RDD[(Int, List[(Int, Double, Double)])]): (Double, Int) = {
      /* calculate error sum of squares and number of observations for a given subset of test data

         Parameters
         ----------
         cntr: Integer; indicator for which subset of the data to look at

         Return
         ------
         partialResult: (Double, Integer); the first element of the tuple is the error sum of squares and the second
                                           element is the count of observations

       */
      //      val embkeys = embeddingRDD.map(_._1).collect()

      //      val toBroadcast =  trainData(cntr).collectAsMap()
      //      val toBroadcast =  trainData(cntr)
      //        .filter{
      //                    case ( x: Int, y:List[(Int, Double)]) =>
      //                      embkeys.contains(y(0)._1)
      //                    }
      //        .filter{
      //        item => embkeys.contains(item._2)
      //      }
      //            .collectAsMap()
      //      val trainUsers = toBroadcast.keys.toArray
      //      val trainBroadcast = sc.broadcast(toBroadcast)
      //      val trainBroadcast = sc.broadcast(trainData(cntr).collectAsMap())

      //        embeddingRDD.map{item => item._1}

      //      val err_sqr =
      ////        testData
      ////          .filter{
      ////          case ( x: Int, y:List[(Int, Double)]) =>
      ////            trainUsers.contains(x)
      ////          }
      //
      ////          .filter{ item => item.map{item._2} embkeys.contains(item._2)}
      //
      ////          .filter{
      ////        case (k,v) =>
      ////          (k<((cntr+1)*20000) && (k>=(cntr*20000)))
      //////      }
      ////          .map{
      ////        case (user, testList) =>
      ////        val trainList = trainBroadcast.value(user)//.filter(_._1 != testMovie)
      ////
      ////
      //
      //
      //            val trainEmbed = trainList.map {
      //
      //              case (movie, rate) =>
      ////                if (embeddings.value(movie) != null )
      //                  (movie, embeddings.value(movie), rate)
      ////                else (movie, Array.ofDim[Double](100), rate)
      ////                              (movie, embeddings.value(3258),rate)
      //
      //
      //            }
      //
      //
      //
      //      val testEmbed = testList.map{
      //        case (testMovie, testRate) =>
      ////          if (embeddings.value(testMovie) != null )
      //            (testMovie,embeddings.value(testMovie),testRate)
      ////          else (testMovie, Array.ofDim[Double](100) ,testRate)
      ////        case (testMovie, testRate) => (testMovie,embeddings.value(3258),testRate)
      ////        case (testMovie, testRate) => (testMovie,embeddings.value(1339),testRate)
      //      }
      //        testEmbed.map{
      //          case (testMovie, testEmbedding, testRate) =>
      //          val trainFilter = trainList.filter(_._1!=testMovie)
      //          val dist = trainEmbed.map {
      //            case (trainMovieId, trainEmbed, trainRate) =>
      //              (cosineSimilarity(testEmbedding, trainEmbed), trainRate)
      //          }
      //          val topItems = dist.sortBy(_._1)(Ordering[Double].reverse).take(numMovies)
      //          val predictedRate = if (typeAvg==0) weightedAverage(topItems) else naiveAverage(topItems)

      //          println(testMovie, testRate, predictedRate)

      val err_sqr = predictions.map {
        case (user, listitems) => listitems.map {
          case (item, trainRate, predictedRate) =>
            val err = trainRate - predictedRate
            //            println(testMovie, err)
            (math.pow(err, 2.0), 1)

        }.reduce((acc, elem) => (acc._1 + elem._1, acc._2 + elem._2))

      }
      val partialResult = err_sqr.reduce((acc, elem) => (acc._1 + elem._1, acc._2 + elem._2))
      partialResult
    }

//      val err = testRate - predictedRate
////            println(testMovie, err)
//          (math.pow(err, 2.0),1)
//        }.reduce((acc, elem) => (acc._1+elem._1, acc._2+elem._2))
//
//      }
//
////      println(err_sqr)
//      val partialResult = err_sqr.reduce((acc, elem) => (acc._1+elem._1, acc._2+elem._2))
////      println(partialResult)
//
//      trainBroadcast.unpersist()
//      partialResult
//    }

    def predict(cntr: Integer, output: String): RDD[(Int, List[(Int, Double, Double)])] = {
      /* Predict scores for train set based on averaging method

         Parameters
         ----------
         cntr: Integer; indicator for which subset of the data to look at
         type of average
         output_filename


         Return
         ------
         partialResult: (Double, Integer); the first element of the tuple is the error sum of squares and the second
                                           element is the count of observations
         return RDD as a variable to do error calculation

       */

      //      # identify the embeddings available
      val embkeys = embeddingRDD.map(_._1).collect()

      // broadcast training set as a hashmap for efficient calculation at the workers
      val toBroadcast =  trainData(cntr).collectAsMap()
      val trainUsers = toBroadcast.keys.toArray
      val trainBroadcast = sc.broadcast(toBroadcast)

      // make prediction for each of the rows in the test set

      val prediction =
        testData
          .filter{
            case ( x: Int, y:List[(Int, Double)]) =>
              trainUsers.contains(x)
          }.map{
          case (user, testList) =>
            //  here is where the filter needs to be placed !!!!!!
//            val itemsOfTestUser = testList.map(_._1).distinct().collect()
//            val itemsOfTestUser = testList(_._1).distinct().collect()

            val trainList = trainBroadcast.value(user)//.filter(_._1 != testMovie)


            val trainEmbed = trainList.map {
              case (movie, rate) =>
                (movie, embeddings.value(movie), rate)
            }

            val testEmbed = testList.map{
              case (testMovie, testRate) =>
                (testMovie,embeddings.value(testMovie),testRate)
            }

            val predictions = testEmbed.map {
              case (testMovie, testEmbedding, testRate) =>
                val trainFilter = trainList.filter(_._1 != testMovie)
                val dist = trainEmbed.map {
                  case (trainMovieId, trainEmbed, trainRate) =>
                    (cosineSimilarity(testEmbedding, trainEmbed), trainRate)
                }
                val topItems = dist.sortBy(_._1)(Ordering[Double].reverse).take(numMovies)
                val predictedRate = if (typeAvg == 0) weightedAverage(topItems) else naiveAverage(topItems)

                (testMovie, testRate, predictedRate)

            }
           (user, predictions)

        }//.reduceByKey(_++_)

      trainBroadcast.unpersist()
//      val schema = StructType(StructField("x", IntegerType, true)::
//        StructField("y", IntegerType, true)::Nil)
//
//      prediction
      if (output != None)
        prediction.coalesce(1).saveAsTextFile(output)

      prediction

    }

//    val (rmseNum, rmseDenom) = (0 to 0).map(i => calcErr(i)).reduce((a,b) => (a._1+b._1, a._2+b._2))
//    (0 to 0).map(i => predict(i))
    val p = predict(0, output="/Users/jaimealmeida/Repos/L2V_Sandbox/rmseNew/rmseconfig/predictions")
//    val (rmseNum, rmseDenom) = (0 to 24).map(i => calcErr(i)).reduce((a,b) => (a._1+b._1, a._2+b._2))
    val (rmseNum, rmseDenom) = calcErr(0, p)
//    embeddings.unpersist()
    val listOutput = List(embedingSize, walkLength, numWalks,math.sqrt(rmseNum/rmseDenom))
    sc.parallelize(listOutput).coalesce(1).saveAsTextFile(outputDir+s"new${embedingSize}_${walkLength}_${numWalks}")

  }

  def main(args: Array[String]) {
    // generate map containing relevant parameters passed by user
//    val s3Bkt = args(0)
    val s3Bkt = "/Users/jaimealmeida/Repos/L2V_Sandbox/rmseNew/rmseconfig/"
//    "/Users/jaimealmeida/Repos/l2v/ratings_train_formatted.txt"


    //initialize spark conference
    val conf = new SparkConf()
      .setAppName("RmseNew")
//      .setMaster("yarn")
      .setMaster("local[*]")
//      .set("spark.dynamicAllocation.enabled","true")
//      .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
//      .set("spark.kryoserializer.buffer", "24000k")
//      .set("spark.kryoserializer.buffer.max", "2047000k")
//      .set("spark.memory.useLegacyMode", "true")
//      .set("spark.shuffle.memoryFraction", ".9")
//      .set("spark.storage.memoryFraction", ".1")

    // create spark context
//    val accessKey = confMap("accessKey")
//    val secretKey = confMap("secretKey")
    implicit val sc = new SparkContext(conf)
    val confMap = getConfMap(s3Bkt+"confRmse.txt")
//    val cred  = new BasicAWSCredentials(accessKey, secretKey)
//    val s3cli = new AmazonS3Client(cred, new ClientConfiguration())
//    val tm = new TransferManager(s3cli)
//    sc.hadoopConfiguration.set("fs.s3a.impl","org.apache.hadoop.fs.s3a.S3AFileSystem")
//    sc.hadoopConfiguration.set("fs.s3a.access.key", accessKey)
//    sc.hadoopConfiguration.set("fs.s3a.secret.key",secretKey)
//    sc.hadoopConfiguration.set("fs.s3.awsAccessKeyId", accessKey)
//    sc.hadoopConfiguration.set("fs.s3.awsSecretAccessKey",listosecretKey)
//    sc.hadoopConfiguration.set("fs.s3a.fast.upload", "true")
//    sc.hadoopConfiguration.set("fs.s3a.connection.maximum","100")

    /* convert train data to a RDD
          key: user id
          value: list of all movies user has reviewed, List[(movie id, rating)]
     */
    val embs = sc.textFile("/Users/jaimealmeida/Repos/L2V_Sandbox/rmseNew/rmseconfig/embeddings/100-10-10.embeddings0")
    val losmismisimos = embs.map{ line =>
      val perone = line.split(" ")
      val embedding = perone.head.toInt
      embedding
    }.distinct.collect()

    val trainDir = s3Bkt+confMap("trainDir")
    val tD1 = getTrainOrTest(trainDir)
    val tdUser = tD1.map(_._1).distinct().collect()
    val tdMovie = tD1.map(_._2).distinct().collect()

    val tDfiltered = tD1.filter{case (i,j,k) => losmismisimos.contains(j)}

    val tD = tDfiltered.map{case (i,j,k) => (i, List((j,k)))}.reduceByKey(_++_)

//    val trainData = (1 to 25).map(i => tD.filter{case (k,v) => (k>= ((i-1)*20000)) && (k<(i*20000))}).toList
    val trainData = (1 to 25).map(i => tD.filter{case (k,v) => (k>= ((i-1)*20000)) && (k<(i*20000))}).toList
//    val trainData = tD



    /* convert test data to a RDD
          key: user id
          value: list of all movies user has reviewed, List[(movie id, rating)]
     */
    val testDir = s3Bkt+confMap("testDir")
    val testData = getTrainOrTest(testDir).filter{case (i,j,k) => tdUser.contains(i) && tdMovie.contains(j) && losmismisimos.contains(j)} // filtering test set on training user/movies
      .map{case (i,j,k) => (i, List((j,k)))}.reduceByKey(_++_)

    /* get hyperparameter map
          key: index of L2V run
          value: Array[(size of embeddings, length of each random walk, number of random walks per node)]
     */
    val hyperParamDir = s3Bkt+confMap("hyperParamDir")
    val hyperParamDirNum = confMap("hyperParamDirNum").toInt
    val hyperParamFiles = (0 to hyperParamDirNum).map{i =>
      val strNum = i.toString
      val zeros = "0"*(5-strNum.length)
      val finalNum = zeros+strNum
      hyperParamDir+s"part-${finalNum}"}.toList
    val hyperParamMap = getParamMap(hyperParamFiles)

    // get the list of all embedding files
    val embeddingDir = s3Bkt+confMap("embeddingDir")
//    val llrSuffix = confMap("llrSuffix")
    val embeddingDirNum = confMap("embeddingDirNum").toInt
//    val embeddingFiles =  (s3Bkt+"/embeddingdir/train_llr_threshold10-10-100.embeddings").toList
    val embeddingFiles = (0 to embeddingDirNum).map{i =>
      embeddingDir+s"100-10-10.embeddings${i}"}.toList

    /* initialize function that return hyperparameters and embedding map for given embedding file
       with the hyperparameter map
     */

    val getEmbed = getEmbedHyperParam(hyperParamMap)_

    // initialize function that calculates RMSE with values that are constant for all sets of embeddings
    val numMovies = confMap("numMovies").toInt
    val typeAvg = confMap("typeAvg").toInt
    val outputDir = s3Bkt+confMap("outputDir")
    val getRmse = rmseCalculate(getEmbed, numMovies, trainData, testData, typeAvg, outputDir)_  // assign function to variable

    // iterate through each embedding file and get its accompanying hyperparameters and rmse
    val rmseOutput = embeddingFiles.map(getRmse)

  }
}
