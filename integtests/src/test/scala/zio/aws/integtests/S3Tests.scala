package zio.aws.integtests

import java.net.URI
import akka.actor.ActorSystem
import zio.aws.core.AwsError
import zio.aws.core.aspects._
import zio.aws.s3.model.{primitives, _}
import zio.aws.s3.model.primitives._
import zio.aws.s3._
import zio.aws.core.config._
import zio.aws.netty._
import zio.aws.http4s._
import zio.aws.akkahttp._
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import zio._
import zio.stream.ZStream
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

object S3Tests extends ZIOSpecDefault with Logging {
  val nettyClient = NettyHttpClient.default
  val http4sClient = Http4sClient.default

  val actorSystem =
    ZLayer.scoped(ZIO.acquireRelease(ZIO.attempt(ActorSystem("test")))(sys =>
      ZIO.fromFuture(_ => sys.terminate()).orDie
    ))
  val akkaHttpClient = AkkaHttpClient.client()

  val awsConfig = AwsConfig.default
  val s3Client = S3.customized(
    _.credentialsProvider(
      StaticCredentialsProvider
        .create(AwsBasicCredentials.create("dummy", "key"))
    ).region(Region.US_WEST_2)
      .endpointOverride(new URI("http://localhost:4566"))
  ) @@ callLogging

  private def testBucket(prefix: String): ZIO[S3, Nothing, ZIO[S3 with Scope, AwsError, primitives.BucketName]] = {
    for {
      s3 <- ZIO.service[S3]
      postfix <- Random.nextInt.map(Math.abs)
      bucketName = BucketName(s"${prefix}-$postfix")
    } yield ZIO.acquireRelease(
      for {
        _ <- Console.printLine(s"Creating bucket $bucketName").ignore
        _ <- S3.createBucket(
          CreateBucketRequest(
            bucket = bucketName
          )
        )
      } yield bucketName
    )(bucketName =>
      (for {
        _ <- Console.printLine(s"Deleting bucket $bucketName").ignore
        _ <- S3.deleteBucket(DeleteBucketRequest(bucketName))
      } yield ())
        .provideEnvironment(ZEnvironment(s3))
        .catchAll(error => ZIO.die(error.toThrowable))
        .unit
    )
  }

  def tests(prefix: String, ignoreUpload: Boolean = false): Seq[ZSpec[TestEnvironment with S3, Throwable]] =
    Seq(
      test("can create and delete a bucket") {
        // simple request/response calls
        val steps = for {
          bucket <- testBucket(s"${prefix}-cd")
          _ <- ZIO.scoped(bucket.unit)
        } yield ()

        assertM(steps.exit)(succeeds(isUnit))
      } @@ nondeterministic @@ flaky @@ timeout(1.minute),
      test(
        "can upload and download items as byte streams with known content length"
      ) {
        // streaming input and streaming output calls
        val steps = for {
          testData <- Random.nextBytes(65536)
          bucket <- testBucket(s"${prefix}-ud")
          key = ObjectKey("testdata")
          receivedData <- ZIO.scoped { 
            bucket.flatMap { bucketName =>
              for {
                _ <- Console.printLine(s"Uploading $key to $bucketName").ignore
                _ <- S3.putObject(
                  PutObjectRequest(
                    bucket = bucketName,
                    key = key,
                    contentLength = Some(
                      ContentLength(65536L)
                    ) // Remove to test https://github.com/vigoo/zio-aws/issues/24
                  ),
                  ZStream
                    .fromIterable(testData)
                    .rechunk(1024)
                )
                _ <- Console.printLine("Downloading").ignore
                getResponse <- S3.getObject(
                  GetObjectRequest(
                    bucket = bucketName,
                    key = key
                  )
                )
                getStream = getResponse.output
                result <- getStream.runCollect

                _ <- Console.printLine("Deleting").ignore
                _ <- S3.deleteObject(
                  DeleteObjectRequest(
                    bucket = bucketName,
                    key = key
                  )
                )

              } yield result
            }
          }
        } yield testData == receivedData      

        assertM(steps.mapError(_.toThrowable))(isTrue)
      } @@ (if (ignoreUpload) ignore
            else identity) @@ nondeterministic @@ flaky @@ timeout(1.minute)
    )

  override def spec: ZSpec[TestEnvironment, Throwable] = {
    suite("S3")(
      suite("with Netty")(
        tests("netty"): _*
      ).provideCustom(
        nettyClient.mapError(TestFailure.fail),  awsConfig, s3Client.mapError(TestFailure.fail)
      ) @@ sequential,
      suite("with http4s")(
        tests("http4s"): _*
      ).provideCustom(
        http4sClient.mapError(TestFailure.fail), awsConfig, s3Client.mapError(TestFailure.fail)
      ) @@ sequential,
      suite("with akka-http")(
        tests("akkahttp", ignoreUpload = true): _*
      ).provideCustom(
        actorSystem.mapError(TestFailure.fail), akkaHttpClient.mapError(TestFailure.fail), awsConfig,  s3Client.mapError(TestFailure.fail)
      ) @@ sequential
    ) @@ sequential
  }
}
