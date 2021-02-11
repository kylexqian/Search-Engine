package edu.northwestern.ssa;


import org.apache.commons.io.IOUtils;
import org.archive.io.ArchiveReader;
import org.archive.io.ArchiveRecord;
import org.archive.io.warc.WARCReaderFactory;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.io.UncheckedIOException;
import java.time.Duration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class App {
    public static void main(String[] args) throws IOException
    {
        System.out.println("Starting!");

        // Part 1: Download WARC
        // object of type software.amazon.awssdk.services.s3.S3Client
        System.out.println("Creating Client");
        S3Client s3 = S3Client.builder()
                .region(Region.US_EAST_1)
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofMinutes(30)).build())
                .build();


        // object of type software.amazon.awssdk.services.s3.model.GetObjectRequest
        String request_key = "";
        // check if crawl filename is empty, if it is find latest WARC file
        if (System.getenv("COMMON_CRAWL_FILENAME") == null || System.getenv("COMMON_CRAWL_FILENAME") == ""){
            System.out.println("no crawl filename set, finding latest...");

            // Note: this code is borrowed from stackoverflow for iterating through s3 buckets (https://stackoverflow.com/questions/8027265/how-to-list-all-aws-s3-objects-in-a-bucket-using-java)
            List<String> objects = new ArrayList<String>();

            // create prefix
            int year = 2021;
            int month = 01;

            String prefix = "crawl-data/CC-NEWS/" + year + "/" + month + "/";

            ListObjectsV2Request request = ListObjectsV2Request.builder()
                    .bucket("commoncrawl")
                    .prefix(prefix)
                    .build();

            // while there are no objects found
            while (objects.size() == 0) {
                ListObjectsV2Iterable response = s3.listObjectsV2Paginator(request);

                month -= 1;
                if (month <= 0) {
                    month = 12;
                    year -= 1;
                }

                prefix = "crawl-data/CC-NEWS/" + year + "/" + month + "/";
                request = ListObjectsV2Request.builder()
                        .bucket("commoncrawl")
                        .prefix(prefix)
                        .build();

                System.out.println("Checking " + prefix);

                for (ListObjectsV2Response page : response) {
                    page.contents().forEach((S3Object object) -> {
                        objects.add(object.key().toString());
                    });
                }
                // end borrow
            }

            // sort array of strings
            String[] objects_sorted =  new String[objects.size()];
            System.out.println("Retreiving...");
            objects.toArray(objects_sorted);

            System.out.println("Sorting...");
            Arrays.sort(objects_sorted);

            request_key = objects_sorted[objects_sorted.length-1];
            System.out.println("Latest key " + request_key);
        }
        else{
            request_key = System.getenv("COMMON_CRAWL_FILENAME");
        }

        // create request
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket("commoncrawl")
                .key(request_key)
                .build();

        // execute request, write to file
        System.out.println("Creating File");

        File f = new File("data.warc");//path.toFile();


        System.out.println("Sending Request");
        s3.getObject(request, ResponseTransformer.toFile(f));

        // Create object of type ArchiveReader
        s3.close();
        System.out.println("s3 closed");


        // Part 2: Parse WARC
        // activate reader

        System.out.println("Initialize Reader");
        ArchiveReader reader = WARCReaderFactory.get(f);

        // Create Elastic Search (part 4)
        System.out.println("Creating Elastic Search Index");
        ElasticSearch ES = new ElasticSearch("es");
        // delete and recreate index
        ES.delete();
        HttpExecuteResponse index = ES.createIndex();
        System.out.println("Looping through records");

        // iterate through ArchiveRecord
        for (ArchiveRecord record: reader) {
            // check if this is a response
            if (!record.getHeader().getMimetype().equals("application/http; msgtype=response")) {
                continue;
            }

            // turn data to byte array (from CampusWIre)
            byte[] data = IOUtils.toByteArray(record, record.available());

            // read data
            record.read(data);
            String contents = new String(data, "utf-8");

            // get URL
            String url = record.getHeader().getUrl();

            // split data up by \r\n\r\n
            String[] split = contents.split("\r\n\r\n", 2);

            String header = split[0];
            String body = split[1];

            // Part 3: Parse HTML
            // Do not consider any messages that do not have at least one blank (no header)
            try {
                Document document = Jsoup.parse(body.replaceAll("\u0000", ""));
                String text = document.text();
                String title = document.title();


                // Part 4: Post the <url, title, txt> to Elasticsearch
                // create JSON file
                JSONObject json = new JSONObject();

                // add url, title, text
                json.put("url", url);
                json.put("title", title);
                json.put("txt", text);

                try{
                    if (!ES.post(json)){
                        System.out.println("Failed to Post");
                    }
                }
                catch (Exception e){
                    System.out.println("post timed out");
                }
            } catch (UncheckedIOException e) {
                System.out.println("Found a non-HTML doc");
            }
        }

        // delete data file and close index
        ES.closeIndex(index);
        f.delete();
    }
}


class ElasticSearch extends AwsSignedRestRequest {
    // Constructor:
    ElasticSearch(String serviceName) {
        super(serviceName);
    }
    // create index method
    public HttpExecuteResponse createIndex() throws IOException {
        HttpExecuteResponse response = super.restRequest(SdkHttpMethod.PUT, System.getenv("ELASTIC_SEARCH_HOST"), System.getenv("ELASTIC_SEARCH_INDEX"), Optional.empty());
        return response;
    }

    // post doc method
    public boolean post(JSONObject json) throws IOException
    {
        // java.util.Optional.of(body);
        HttpExecuteResponse response = super.restRequest(SdkHttpMethod.POST, System.getenv("ELASTIC_SEARCH_HOST"), System.getenv("ELASTIC_SEARCH_INDEX") + "/_doc/", Optional.empty(), java.util.Optional.of(json));
        response.responseBody().get().close();
        return response.httpResponse().isSuccessful();
    }

    // delete doc method
    public boolean delete() throws IOException {
        HttpExecuteResponse response = super.restRequest(SdkHttpMethod.DELETE, System.getenv("ELASTIC_SEARCH_HOST"), "my-index", Optional.empty());
        response.responseBody().get().close();
        return response.httpResponse().isSuccessful();
    }

    // close
    public void closeIndex(HttpExecuteResponse response) throws IOException {
        response.responseBody().get().close();
    }

}






