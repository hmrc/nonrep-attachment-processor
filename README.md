
# nonrep-attachment-processor

The **attachment-processor** service runs a streaming service that takes messages from SQS, 
processing each one before submitting the bundle to Metastore.
Processing involves taking each bundle and signing to attachments to create a zip file that can be stored in Metastore.


## Endpoints

There are no endpoint to perform requests, once started the service listen to a SQS and handles requests.
The only _system_ endpoints are:

*   [Ping](#ping)
*   [Version](#version)


#### Ping <a name="ping"></a>
This endpoint can be used as a lightweight health check for the availability of the service
```
GET /attachment-processor/ping
```
Returns: 
```
pong
```



#### Version <a name="version"></a>

This endpoint returns the deployed version designation of this service
```
GET /attachment-processor/version
```
Sample response:
```json
{ "version": "20201118T150059" }
```


# Running the application

To run locally:

>   ```sbt run```

(default port: 8000)

## How to test
>   ```sbt clean test it:test```

# Check and reformat code

All code should be formatted before being pushed, to check the format
>   ```sbt scalafmtCheckAll it:scalafmtCheckAll```

and reformat if required:

>   ```sbt scalafmtAll it:scalafmtAll```


# License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
