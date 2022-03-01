package uk.gov.hmrc.nonrep.attachment

import software.amazon.awssdk.services.sqs.model.Message

import java.io.File
import java.nio.file.Files.readAllBytes
import java.util.UUID.randomUUID

trait DataSamples {

  val sqsMessage: String => String = uuid => s"""{
   "Records":[{
     "eventVersion":"2.0",
     "eventSource":"aws:s3",
     "awsRegion":"eu-west-2",
     "eventTime":"2018-07-19T10:09:23.582Z",
     "eventName":"ObjectCreated:Put",
     "userIdentity":{
        "principalId":"AWS:AROAJEF65UXX45FLSXW64:NrsSandboxAccSession"
     },
     "requestParameters":{
        "sourceIPAddress":"163.171.34.210"
     },
     "responseElements":{
        "x-amz-request-id":"B7920A65BC53684D",
        "x-amz-id-2":"o1SKKRM4L9+gbNDpimZLuuciL5jMg5WBcNyt1DC3wavZ2j9tsxL5djGIZseFjHhi0tGLzY6C28k="
     },
     "s3":{
        "s3SchemaVersion":"1.0",
        "configurationId":"tf-s3-queue-20180719100701291800000001",
        "bucket":{
           "name":"test-nonrep-attachment-processor-data",
           "ownerIdentity":{
              "principalId":"A202PFQUTJVUOI"
           },
           "arn":"arn:aws:s3:::test-nonrep-attachment-processor-data"
        },
        "object":{
           "key":"$uuid.zip",
           "size":717,
           "eTag":"5014df6e23f495fd0b0aca65606b37b6",
           "sequencer":"005B50635374A08FD3"
        }
     }
      }]
  }"""

  val sqsMessageS3TestEvent = """{
   "Service":"Amazon S3",
   "Event":"s3:TestEvent",
   "Time":"2018-07-19T10:07:01.341Z",
   "Bucket":"test-nonrep-attachment-processor-data",
   "RequestId":"182E515D8BC1CC07",
   "HostId":"ezZTcYb6uxrISBtUg0Ns7AuYVrJx/erWBnPod7eereQCywRyW/e/Vz69jQm3Of71hyDeEhB89kM="
  }"""

  private def createSQSMessage = {
    val uuid = randomUUID().toString
    Message.builder().receiptHandle(uuid).body(sqsMessage(uuid)).build()
  }

  val sqsMessages: List[Message] = List(createSQSMessage, createSQSMessage, createSQSMessage)

  lazy val bundle: Array[Byte] = readAllBytes(new File(getClass.getClassLoader.getResource("e51c3dd5-aa1a-4e3e-8862-635a11836dfb.zip").getFile).toPath)
}