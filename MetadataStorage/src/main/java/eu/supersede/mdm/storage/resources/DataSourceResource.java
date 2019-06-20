package eu.supersede.mdm.storage.resources;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.mongodb.MongoClient;
import com.mongodb.client.MongoCollection;
import eu.supersede.mdm.storage.model.Namespaces;
import eu.supersede.mdm.storage.model.metamodel.SourceGraph;
import eu.supersede.mdm.storage.parsers.OWLtoD3;
import eu.supersede.mdm.storage.service.impl.DeleteDataSourceServiceImpl;
import eu.supersede.mdm.storage.service.impl.DeleteWrapperServiceImpl;
import eu.supersede.mdm.storage.util.ConfigManager;
import eu.supersede.mdm.storage.util.MongoCollections;
import eu.supersede.mdm.storage.util.RDFUtil;
import eu.supersede.mdm.storage.util.Utils;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import net.minidev.json.JSONObject;
import net.minidev.json.JSONValue;
import org.bson.Document;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Created by snadal on 22/11/16.
 */
@Path("metadataStorage")
public class DataSourceResource {

    private static final Logger LOGGER = Logger.getLogger(DataSourceResource.class.getName());

    @GET
    @Path("dataSource/")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_PLAIN)
    public Response GET_dataSource() {
        System.out.println("[GET /GET_dataSource/]");
        MongoClient client = Utils.getMongoDBClient();
        List<String> dataSources = Lists.newArrayList();
        MongoCollections.getDataSourcesCollection(client).find().iterator().forEachRemaining(document -> dataSources.add(document.toJson()));
        client.close();
        return Response.ok(new Gson().toJson(dataSources)).build();
    }

    @GET
    @Path("dataSource/{dataSourceID}")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_PLAIN)
    public Response GET_dataSourceByID(@PathParam("dataSourceID") String dataSourceID) {
        System.out.println("[GET /dataSource/] dataSourceID = " + dataSourceID);
        MongoClient client = Utils.getMongoDBClient();
        Document query = new Document("dataSourceID", dataSourceID);
        Document res = MongoCollections.getDataSourcesCollection(client).find(query).first();
        client.close();
        return Response.ok((res.toJson())).build();
    }

    @POST
    @Path("dataSource/")
    @Consumes("text/plain")
    public Response POST_dataSource(String body) {
        System.out.println("[POST /dataSource/] body = " + body);
        JSONObject objBody = (JSONObject) JSONValue.parse(body);
        MongoClient client = Utils.getMongoDBClient();
        String dsName = objBody.getAsString("name").trim().replace(" ","");
        String iri = SourceGraph.DATA_SOURCE.val()+"/"+dsName;
        //Save metadata
        objBody.put("dataSourceID", UUID.randomUUID().toString().replace("-",""));
        objBody.put("iri", iri);
        objBody.put("bootstrappingType", "manual");
        MongoCollections.getDataSourcesCollection(client).insertOne(Document.parse(objBody.toJSONString()));

        RDFUtil.addTriple(iri, iri, Namespaces.rdf.val()+"type", SourceGraph.DATA_SOURCE.val());

        objBody.put("rdf",RDFUtil.getRDFString(iri));

        client.close();
        return Response.ok(objBody.toJSONString()).build();
    }

    @ApiOperation(value = "Delete a Data Source and its related Wrappers and LAVMappings, if exist.",consumes = MediaType.TEXT_PLAIN)
    @ApiResponses(value ={
            @ApiResponse(code = 200, message = "OK")})
    @DELETE @Path("dataSource/{dataSourceID}")
    @Consumes("text/plain")
    public Response DELETE_DataSourceByID(@PathParam("dataSourceID") String dataSourceID) {
        LOGGER.info("[DELETE /dataSource/ "+dataSourceID);
        DeleteDataSourceServiceImpl del = new DeleteDataSourceServiceImpl();
        del.delete(dataSourceID);
        return Response.ok().build();
    }

    /**
     * Get the graphical representation of the data source
     */
    /*
    @GET @Path("dataSource/{iri}/graphical")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_PLAIN)
    public Response GET_artifact_content_graphical(@PathParam("iri") String iri) {
        System.out.println("[GET /dataSource/"+iri+"/graphical");
        Dataset dataset = Utils.getTDBDataset();
        dataset.begin(ReadWrite.READ);
        List<Tuple3<Resource,Property,Resource>> triples = Lists.newArrayList();
        RDFUtil.runAQuery("SELECT * WHERE { GRAPH <"+iri+"> {?s ?p ?o} }",  dataset).forEachRemaining(triple -> {
            triples.add(new Tuple3<>(new ResourceImpl(triple.get("s").toString()),
                    new PropertyImpl(triple.get("p").toString()),new ResourceImpl(triple.get("o").toString())));
        });
        String JSON = OWLtoD3.parse(artifactType, triples);
        dataset.end();
        dataset.close();
        return Response.ok((JSON)).build();
    }
    */
}