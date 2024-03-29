package eu.supersede.mdm.storage.bdi.mdm.constructs;

import com.google.common.collect.ImmutableMap;
import com.mongodb.MongoClient;
import com.mongodb.client.MongoCursor;
import eu.supersede.mdm.storage.bdi.extraction.Namespaces;
import eu.supersede.mdm.storage.model.metamodel.GlobalGraph;
import eu.supersede.mdm.storage.resources.LAVMappingResource;
import eu.supersede.mdm.storage.resources.WrapperResource;
import eu.supersede.mdm.storage.util.MongoCollections;
import eu.supersede.mdm.storage.util.RDFUtil;
import eu.supersede.mdm.storage.util.Tuple3;
import eu.supersede.mdm.storage.util.Utils;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import net.minidev.json.JSONValue;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.impl.PropertyImpl;
import org.apache.jena.rdf.model.impl.ResourceImpl;
import org.bson.Document;
import org.semarglproject.vocab.OWL;
import org.semarglproject.vocab.RDF;
import org.semarglproject.vocab.RDFS;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Created by Kashif-Rabbani in June 2019
 */
public class MDMLavMapping {
    private static final Logger LOGGER = Logger.getLogger(MDMLavMapping.class.getName());
    private String mdmGlobalGraphIri;
    private String mdmGgId;
    private JSONArray wrappersMongoInformation = new JSONArray();
    private JSONArray wrappersCoveringGlobalGraph;
    //private List<Tuple2<String, String>> lavMappings = new ArrayList<>();
    private JSONObject lavMapping;
    private JSONArray featureAndAttributes;
    /*Map<feature, List< Tuple3<localName, sourceName, IRI>, Tuple3<,,>,....*/
    private Map<String, List<Tuple3<String, String, String>>> features = new HashMap<>();
    HashMap<String, String> nodesIds;

    public MDMLavMapping(String mdmGlobalGraphIri, HashMap<String, String> nodesIds) {
        this.mdmGlobalGraphIri = mdmGlobalGraphIri;
        this.nodesIds = nodesIds;
        run();
    }

    public MDMLavMapping(String mdmGlobalGraphIri) {
        this.mdmGlobalGraphIri = mdmGlobalGraphIri;
        run();
    }

    private void run() {
        getFeaturesWithSameAsEdges();
        System.out.println("FEATURES:");
        System.out.println(features);
        getWrapperInfoFromGg();
        initLavMapping();
        initViewOfSourceGraphsOverGlobalGraph();
    }

    /**
     * This method is to extract all the features from the MDM global Graph which was generated automatically from Metadata Traceability graph
     * It fills a map with values like this:
     * {feature, [tuple3< _1:LocalName, _2: Source Name, _3: IRI>, tuple3<>, ...]} e.g. one of the real example is shown below:
     * {http://www.BDIOntology.com/global/uKHAoVbK-kphiyJAL/Registration_Number=
     * [
     * Tuple3{_1=Registration_Number, _2=Bicycles, _3=http://www.BDIOntology.com/schema/Bicycles/Bicycle/Registration_Number},
     * Tuple3{_1=Registration_Number, _2=Bikes, _3=http://www.BDIOntology.com/schema/Bikes/Bike/Registration_Number}
     * ],  http://www.BDIOntology.com/schema/Bicycles/Bicing_Details/Total_Clients=[]}
     * In the above example, a feature with a global IRI is pointing to two other features with a sameAs relationship.
     * Note that the features having localIRIs are not pointing to any other features
     * e.g. in the above example, the feature ..../Total_Clients has empty list for sameAs features. Which states that it does not have any relationship with any other feature.
     * As MDM does not support features to features relationships in its global graph. We need to subsume these intelligently
     */
    private void getFeaturesWithSameAsEdges() {
        String SPARQL = "SELECT * WHERE { GRAPH <" + mdmGlobalGraphIri + "> { ?f rdf:type <" + GlobalGraph.FEATURE.val() + "> . OPTIONAL {?f G:sameAs ?o.} } }";
        RDFUtil.runAQuery(RDFUtil.sparqlQueryPrefixes + SPARQL, mdmGlobalGraphIri).forEachRemaining(triple -> {
            //System.out.print(triple.getResource("f") + "\t");
            //System.out.print(triple.get("o") + "\n");

            //String featureName = triple.get("f").toString().split("/")[triple.get("f").toString().split("/").length - 1];
            String featureName = triple.get("f").toString();
            List<Tuple3<String, String, String>> sameAsfeatures = new ArrayList<>();

            if (triple.get("o") != null)
                sameAsfeatures.add(new Tuple3<>(getLastElementOfIRI(triple.get("o").toString()),
                        getSourceFromIRI(triple.get("o").toString()), triple.get("o").toString()));

            if (features.containsKey(featureName)) {
                //System.out.println("Printing value of the key : " + features.get(featureName));
                List<Tuple3<String, String, String>> tempList = features.get(featureName);
                if (triple.get("o") != null)
                    tempList.add(new Tuple3<>(getLastElementOfIRI(triple.get("o").toString()),
                            getSourceFromIRI(triple.get("o").toString()), triple.get("o").toString()));
                features.put(featureName, tempList);
            } else {
                features.put(featureName, sameAsfeatures);
            }

        });
        //System.out.println(features);
    }


    /**
     * This method is to know that the generated MDM global graph is merger of how many sources e.g. BikesBicyclesCycles Metadata traceability graph is a result of
     * 3 sources. So there must be 3 wrappers associated for the generated MDM global graph.
     * Let's get the ids of the associated wrappers and store them in JsonArray named wrappersCoveringGlobalGraph
     */
    private void getWrapperInfoFromGg() {
        MongoClient client = Utils.getMongoDBClient();
        MongoCursor<Document> cursor = MongoCollections.getGlobalGraphCollection(client).find(new Document("namedGraph", mdmGlobalGraphIri)).iterator();

        JSONObject ggInfo = (JSONObject) JSONValue.parse(MongoCollections.getMongoObject(client, cursor));
        wrappersCoveringGlobalGraph = (JSONArray) ggInfo.get("wrappers");
        mdmGgId = ggInfo.getAsString("globalGraphID");
        client.close();
    }

    /**
     * This method is to create LAV mappings i.e. feature sameAs attribute relationships
     */
    private void initLavMapping() {
        //Let's iterate over all the wrappers
        wrappersCoveringGlobalGraph.forEach(wrapperId -> {
            MongoClient client = Utils.getMongoDBClient();
            MongoCursor<Document> wrapperCursor = MongoCollections.getWrappersCollection(client).
                    find(new Document("wrapperID", wrapperId)).iterator();
            JSONObject wrapperInfo = (JSONObject) JSONValue.parse(MongoCollections.getMongoObject(client, wrapperCursor));

            //For each wrapper call this method to get the job done
            createLavMappings(wrapperInfo.getAsString("iri"));

            lavMapping = new JSONObject();
            lavMapping.put("wrapperID", wrapperId.toString());
            lavMapping.put("isModified", "false");

            lavMapping.put("globalGraphID", mdmGgId);
            lavMapping.put("sameAs", featureAndAttributes);
            //System.out.println(lavMapping.toJSONString());
            LOGGER.info("FeaturesAndAttributes for this wrapper: ");
            LOGGER.info(featureAndAttributes.toJSONString());

            // Call LAV Mapping Resource to save the LAV mapping info accordingly
            JSONObject lavMappingResourceInfo = LAVMappingResource.createLAVMappingMapsTo(lavMapping.toJSONString());
            //System.out.println(lavMappingResourceInfo);
            wrapperInfo.put("LAVMappingID", lavMappingResourceInfo.getAsString("LAVMappingID"));
            wrappersMongoInformation.add(wrapperInfo);
            client.close();
        });

    }

    private void createLavMappings(String wrapperIri) {
        //Get attributes of all the wrappers
        JSONArray wrapperAttributes = WrapperResource.getWrapperAttributes(wrapperIri);
        //System.out.println(wrapperAttributes.toJSONString());
        featureAndAttributes = new JSONArray();

        // iterate over all the wrapper attributes
        wrapperAttributes.forEach(attr -> {
            String attribute = getLastElementOfIRI(attr.toString());

            // Now consider the map containing features as explained above
            features.forEach((key, list) -> {
                //Check if the list is empty or not, if it is empty it means the current feature does not have any sameAs edge to other features
                //It also indicates that the current feature is not the one having global IRI (MOST PROBABLY)
                if (list.isEmpty()) {

                    /*Check the type of the feature and the attribute*/
                    if (attribute.equals(getLastElementOfIRI(key)) && getSourceFromIRI(key).equals(getWrapperSourceFromIRI(attr.toString()))) {
                        //System.out.println("Key: " + key);
                        //lavMappings.add(new Tuple2<>(attr.toString(), key));
                        JSONObject temp = new JSONObject();
                        temp.put("feature", key);
                        temp.put("attribute", attr.toString());
                        featureAndAttributes.add(temp);
                    }
                } else {
                    /*For Schema IRI, source type can be checked*/
                    list.forEach(tuple -> {
                        if (tuple._1.equals(attribute) && tuple._2.equals(getWrapperSourceFromIRI(attr.toString()))) {
                            //System.out.println("Key: " + key + " LocalName: " + tuple._1  + " Source: " + tuple._2);
                            //lavMappings.add(new Tuple2<>(attr.toString(), key));
                            JSONObject temp = new JSONObject();
                            temp.put("feature", key);
                            temp.put("attribute", attr.toString());
                            featureAndAttributes.add(temp);
                        }
                    });

                }
            });
        });
    }

    private void initViewOfSourceGraphsOverGlobalGraph() {
        /*Create a list containing Schema IRIs of all the sources/wrappers involved in the creation of GG*/
        HashMap<String, String> sourceSchemasIRIs = new HashMap<>();
        wrappersMongoInformation.forEach(wrapper -> {
            sourceSchemasIRIs.put(getDataSourceSchemaIRI((JSONObject) wrapper), getDataSourceSchemaIRI((JSONObject) wrapper));
        });
        ImmutableMap<String, String> immutableMap = ImmutableMap.copyOf(sourceSchemasIRIs);
        wrappersMongoInformation.forEach(wrapper -> {
            drawCovering((JSONObject) wrapper, immutableMap);
        });
    }

    private void drawCovering(JSONObject wrapperInfo, ImmutableMap<String, String> sourceSchemasIRIs) {
        LOGGER.info("[Create LAV Covering for Wrapper: " + wrapperInfo.getAsString("name") + " ]");
        /*Using dataSourceID for the wrapper, get the Schema IRI of the source*/
        String dataSourceSchemaIri = getDataSourceSchemaIRI(wrapperInfo);
        HashMap<String, String> temp = new HashMap<>();
        sourceSchemasIRIs.forEach(temp::put);
        temp.remove(dataSourceSchemaIri);

        List<Triple> triples = new ArrayList<>();
        RDFUtil.runAQuery(RDFUtil.sparqlQueryPrefixes + " SELECT ?s ?p ?o WHERE { GRAPH <" + mdmGlobalGraphIri + "> { ?s ?p ?o . }}", mdmGlobalGraphIri).forEachRemaining(res -> {
            //System.out.println(res.get("s").toString() + "\t" + res.get("p").toString() + "\t" + res.get("o").toString());
            triples.add(new Triple(new ResourceImpl(res.get("s").toString()).asNode(), new PropertyImpl(res.get("p").asResource().toString()).asNode(), new ResourceImpl(res.get("o").toString()).asNode()));
        });
        JSONObject lavMappingSubGraph = new JSONObject();
        JSONArray selectionArray = new JSONArray();
        JSONArray graphicalGraphArray = new JSONArray();

        triples.forEach(triple -> {
            if (triple.getSubject().getURI().contains(dataSourceSchemaIri) || triple.getObject().getURI().contains(dataSourceSchemaIri) || triple.getSubject().getURI().contains(Namespaces.G.val())) {
                /*Avoiding other sources IRIs*/
                if (!temp.containsKey(getSourceIRIFromIRI(triple.getObject().getURI()))) {

                    //System.out.println(triple.getSubject().toString() + " " + triple.getPredicate().toString() + " " + triple.getObject().toString());
                    //System.out.println(triple.toString());

                    //Filter features
                    if (triple.getObject().getURI().equals(GlobalGraph.FEATURE.val())) {
                        //System.out.println("Feature: "+ triple);
                        selectionArray.add(createObject(triple.getSubject().getURI(), triple.getSubject().getURI(), triple.getObject().getURI()));
                        graphicalGraphArray.add(nodesIds.get(triple.getSubject().getURI()));
                    }
                    //Filter  concepts
                    if (triple.getObject().getURI().equals(GlobalGraph.CONCEPT.val())) {
                        //System.out.println("Concept: "+ triple);
                        selectionArray.add(createObject(triple.getSubject().getURI(), triple.getSubject().getURI(), triple.getObject().getURI()));
                        graphicalGraphArray.add(nodesIds.get(triple.getSubject().getURI()));
                    }
                    /*Create source and target i.e. edges, for same as relationship */
                    if (triple.getPredicate().getURI().equals(GlobalGraph.SAME_AS.val())) {
                        //System.out.println("SAME AS: "+ triple);
                        //selectionArray.add( createObject(triple.getObject().getURI() , triple.getObject().getURI(), GlobalGraph.FEATURE.val() ));
                        //graphicalGraphArray.add(nodesIds.get(triple.getObject().getURI()));

                        JSONObject obj = new JSONObject();
                        obj.put("source", createObject(triple.getSubject().getURI(), triple.getSubject().getURI(), GlobalGraph.FEATURE.val()));
                        obj.put("target", createObject(triple.getObject().getURI(), triple.getObject().getURI(), GlobalGraph.FEATURE.val()));
                        obj.put("name", GlobalGraph.SAME_AS.val());
                        obj.put("iri", GlobalGraph.SAME_AS.val());
                        selectionArray.add(obj);

                    }
                    /*Create source and target i.e. edges, by connecting Concepts with Features*/
                    if (triple.getPredicate().getURI().equals(GlobalGraph.HAS_FEATURE.val())) {
                        //System.out.println(triple);
                        JSONObject obj = new JSONObject();
                        obj.put("source", createObject(triple.getSubject().getURI(), triple.getSubject().getURI(), GlobalGraph.CONCEPT.val()));
                        obj.put("target", createObject(triple.getObject().getURI(), triple.getObject().getURI(), GlobalGraph.FEATURE.val()));
                        obj.put("name", GlobalGraph.HAS_FEATURE.val());
                        obj.put("iri", GlobalGraph.HAS_FEATURE.val());
                        selectionArray.add(obj);
                    }

                    /*Create source and target i.e. edges, for subClassOf  relationship i.e. Connect Concept with Concept*/
                    if (triple.getPredicate().getURI().equals(RDFS.SUB_CLASS_OF)) {
                        //System.out.println(" SUB CLASS OF: "+ triple);
                        JSONObject obj = new JSONObject();
                        obj.put("source", createObject(triple.getSubject().getURI(), triple.getSubject().getURI(), GlobalGraph.CONCEPT.val()));
                        obj.put("target", createObject(triple.getObject().getURI(), triple.getObject().getURI(), GlobalGraph.CONCEPT.val()));
                        obj.put("name", RDFS.SUB_CLASS_OF);
                        obj.put("iri", GlobalGraph.HAS_RELATION.val());
                        selectionArray.add(obj);

                    }
                    /*Connecting concepts with concepts which are not subClasses*/
                    if (!triple.getPredicate().getURI().equals(RDFS.SUB_CLASS_OF) && !triple.getPredicate().getURI().equals(OWL.SAME_AS) &&
                            !triple.getPredicate().getURI().equals(RDF.TYPE) && !triple.getPredicate().getURI().equals(GlobalGraph.HAS_FEATURE.val())) {
                        //System.out.println(triple);
                        JSONObject obj = new JSONObject();
                        obj.put("source", createObject(triple.getSubject().getURI(), triple.getSubject().getURI(), GlobalGraph.CONCEPT.val()));
                        obj.put("target", createObject(triple.getObject().getURI(), triple.getObject().getURI(), GlobalGraph.CONCEPT.val()));
                        obj.put("name", triple.getPredicate().getURI());
                        obj.put("iri", GlobalGraph.HAS_RELATION.val());
                        selectionArray.add(obj);
                    }

                }
            }
        });
        lavMappingSubGraph.put("selection", selectionArray);
        lavMappingSubGraph.put("graphicalSubGraph", graphicalGraphArray);
        lavMappingSubGraph.put("LAVMappingID", wrapperInfo.getAsString("LAVMappingID"));

        //System.out.println(lavMappingSubGraph);
        LAVMappingResource.createLAVMappingSubgraph(lavMappingSubGraph.toJSONString());
    }

    private JSONObject createObject(String iri, String name, String namespace) {
        JSONObject temp = new JSONObject();
        temp.put("id", nodesIds.get(iri));
        temp.put("iri", iri);
        temp.put("name", name);
        temp.put("namespace", namespace);
        return temp;
    }

    private String getDataSourceSchemaIRI(JSONObject obj) {
        String iri = "";
        MongoClient client = Utils.getMongoDBClient();
        MongoCursor<Document> dataSourceCursor = MongoCollections.getDataSourcesCollection(client).
                find(new Document("dataSourceID", obj.getAsString("dataSourceID"))).iterator();
        JSONObject dataSourceInfo = (JSONObject) JSONValue.parse(MongoCollections.getMongoObject(client, dataSourceCursor));
        iri = dataSourceInfo.getAsString("schema_iri");
        client.close();
        return iri;
    }

    private String getLastElementOfIRI(String iri) {
        return iri.split("/")[iri.split("/").length - 1];
    }

    private String getWrapperSourceFromIRI(String iri) {
        return iri.split("/")[iri.split("/").length - 2];
    }

    private String getSourceFromIRI(String iri) {
        // If the global IRI is of a source, e.g. http://www.BDIOntology.com/schema/Bicycles/Bicycle_Manufacturer then
        // the word after http://www.BDIOntology.com/schema/ is the name of the source. However, if the global IRI is from global
        // instances (created while aligning) e.g. http://www.BDIOntology.com/global/ermaElU0-QbtrOURF/Model, then we can not identify the source from this IRI,
        String source = "";
        if (iri.contains(Namespaces.G.val())) {
            source = "global";
        }

        if (iri.contains(Namespaces.Schema.val())) {
            /*Extract the source name from the IRI*/
            /*Source Name is Bicycle in this IRI http://www.BDIOntology.com/schema/Bicycles/Bicycle_Manufacturer */
            source = iri.split(Namespaces.Schema.val())[1].split("/")[0];
        }

        return source;
    }

    private String getSourceIRIFromIRI(String iri) {
        String sourceIri = iri;
        if (iri.contains(Namespaces.Schema.val())) {
            sourceIri = Namespaces.Schema.val() + iri.split(Namespaces.Schema.val())[1].split("/")[0];
        }
        return sourceIri;
    }
}
