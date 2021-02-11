package edu.northwestern.ssa.api;

//import org.apache.commons.io.IOUtils;
import edu.northwestern.ssa.AwsSignedRestRequest;
import edu.northwestern.ssa.Config;
import org.json.JSONArray;
import org.json.JSONObject;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpMethod;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;



@Path("/search")
public class Search {

    /** when testing, this is reachable at http://localhost:8080/api/search?query=hello */
    @GET
    public Response getMsg(@QueryParam("query") String q, @QueryParam("language") String l, @QueryParam("date") String d, @QueryParam("count") String c, @QueryParam("offset") String o) throws IOException {
        // parse responses for request
        // Map of <string, string>
        Map<String,String> params = new HashMap<String,String>();

        // check q
        if (q == null){
            // if q is null, return 400
            return Response.status(400).entity("Query parameter is empty").type("application/json").build(); // taken from OH
        }

        String q_data = "";
        String[] split_q = q.split(" ");
        String string_q = split_q[0];
        for (int i=1; i < split_q.length; i++){
            string_q += " AND " + split_q[i];
        }
        q_data += "txt:" + "(" + string_q + ")"; // added () because of OH

        // check language
        if (l != null){
            q_data += " AND lang:" + l;
        }

        // check date
        if (d != null){
            // should be in YYYY-MM-DD format
            String string_d = "[" + d + " TO " + d + "]";
            q_data += " AND date:" + d;
        }

        // add q to larger json
        params.put("q", q_data);

        // check count
        if (c != null && !c.isEmpty()){
            params.put("size", c);
        }
        else{ // default
            params.put("size", "10");
        }
        // check offset
        if (o != null && !o.isEmpty()){
            params.put("from", o);
        }
        else{ // default
            params.put("from", "0");
        }

        // from OH
        params.put("track_total_hits", "true");

        System.out.println("PARAMAMAMAMS: " + params);

        // make request
        AwsSignedRestRequest request = new AwsSignedRestRequest("es");
        HttpExecuteResponse response = request.restRequest(SdkHttpMethod.GET, Config.getParam("ELASTIC_SEARCH_HOST"), Config.getParam("ELASTIC_SEARCH_INDEX") + "/_search", java.util.Optional.of(params));

        // parse response using buffered reader
        InputStreamReader stream = new InputStreamReader(response.responseBody().get());
        BufferedReader reader = new BufferedReader(stream);

        // from stackoverflow
        String data = "";
        String currLine;
        while ((currLine = reader.readLine()) != null){
            data += (currLine);
        }

        // convert to JSON object
        JSONObject json = new JSONObject(data.toString());

        // Parse JSON for data we want
        // total results
        JSONObject hits_1 = (JSONObject) json.get("hits");
        JSONObject total = (JSONObject) hits_1.get("total");
        int total_results = total.getInt("value");;

        // articles
        JSONArray articles = new JSONArray();
        JSONArray hits_2 = (JSONArray) hits_1.get("hits");

        // returned results (just keep a counter)
        int returned_results = 0;

        System.out.println(data);

        for (Object obj: hits_2){
            returned_results += 1;
            // create article JSON Object to be added to JSONArray
            JSONObject article = new JSONObject();

            // cast into json object so we can get source, and put that into another json object var!
            JSONObject source = (JSONObject) ((JSONObject) obj).get("_source");

            // article data
            String title = (String) source.get("title");

            String url = (String) source.get("url");

            String text = (String) source.get("txt");

            // if no date, just return null
            String date = null;
            try{
                date = (String) source.get("date");
            }
            catch (Exception e){
                ;
            }
            // if no lang just return null
            String lang = null;
            try{
                lang = (String) source.get("lang");
            }
            catch (Exception e){
                ;
            }

            // fill article
            article.put("title", title);
            article.put("url", url);
            article.put("txt", text);
            article.put("date", date);
            article.put("lang", lang);

            // put article in articles
            articles.put(article);
        }

//        System.out.println("RETURNED: " + returned_results);
//        System.out.println("ARTICLES: " + articles);

        // final JSON object
        JSONObject results = new JSONObject();

        // insert data into json object
        results.put("returned_results", returned_results);
        results.put("total_results", total_results);
        results.put("articles", articles);

        return Response.status(200).type("application/json").entity(results.toString(4))
                // below header is for CORS
                .header("Access-Control-Allow-Origin", "*").build();
    }



}
