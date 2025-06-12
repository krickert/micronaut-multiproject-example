import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.util.ProtobufTestDataHelper;
import java.util.List;

public class Analyze99Documents {
    public static void main(String[] args) {
        System.out.println("=== Analyzing 99 Test Documents ===\n");
        
        // Load all 99 documents
        List<PipeDoc> documents = ProtobufTestDataHelper.getOrderedSamplePipeDocuments();
        System.out.println("Loaded " + documents.size() + " documents\n");
        
        int docsWithBody = 0;
        int docsWithoutBody = 0;
        int totalBodyChars = 0;
        int minBodyLength = Integer.MAX_VALUE;
        int maxBodyLength = 0;
        
        // Analyze each document
        for (int i = 0; i < documents.size(); i++) {
            PipeDoc doc = documents.get(i);
            boolean hasBody = doc.getBody() != null && !doc.getBody().isEmpty();
            
            if (hasBody) {
                docsWithBody++;
                int bodyLength = doc.getBody().length();
                totalBodyChars += bodyLength;
                minBodyLength = Math.min(minBodyLength, bodyLength);
                maxBodyLength = Math.max(maxBodyLength, bodyLength);
            } else {
                docsWithoutBody++;
            }
            
            // Print first 5 and last 5 documents
            if (i < 5 || i >= 94) {
                System.out.printf("Document %02d: ID=%-20s Title=%-30s Body=%d chars\n", 
                    i, 
                    doc.getId(), 
                    doc.getTitle().isEmpty() ? "(no title)" : 
                        (doc.getTitle().length() > 30 ? doc.getTitle().substring(0, 27) + "..." : doc.getTitle()),
                    hasBody ? doc.getBody().length() : 0);
            } else if (i == 5) {
                System.out.println("... (documents 5-93 omitted) ...");
            }
        }
        
        System.out.println("\n=== Document Analysis Summary ===");
        System.out.println("Total documents: " + documents.size());
        System.out.println("Documents with body: " + docsWithBody);
        System.out.println("Documents without body: " + docsWithoutBody);
        System.out.println("Average body length: " + (docsWithBody > 0 ? totalBodyChars / docsWithBody : 0) + " chars");
        System.out.println("Min body length: " + (docsWithBody > 0 ? minBodyLength : 0) + " chars");
        System.out.println("Max body length: " + maxBodyLength + " chars");
        
        // Calculate expected pipeline output
        int chunksPerDoc = 2;
        int embeddingModels = 3;
        int vectorSetsPerDoc = chunksPerDoc * embeddingModels;
        int totalExpectedVectorSets = docsWithBody * vectorSetsPerDoc;
        
        System.out.println("\n=== Expected Pipeline Output ===");
        System.out.println("Configuration:");
        System.out.println("  Chunks per document: " + chunksPerDoc);
        System.out.println("  Embedding models: " + embeddingModels);
        System.out.println("  Vector sets per document: " + vectorSetsPerDoc);
        
        System.out.println("\nExpected Results:");
        System.out.println("  Documents with body: " + docsWithBody + " × " + vectorSetsPerDoc + " = " + 
            (docsWithBody * vectorSetsPerDoc) + " vector sets");
        System.out.println("  Documents without body: " + docsWithoutBody + " × 0 = 0 vector sets");
        System.out.println("  Total expected vector sets: " + totalExpectedVectorSets);
        
        System.out.println("\n=== Memory Recommendations ===");
        System.out.println("With " + embeddingModels + " embedding models processing " + docsWithBody + 
            " documents:");
        System.out.println("  Recommended heap size: -Xmx10g");
        System.out.println("  Recommended metaspace: -XX:MaxMetaspaceSize=512m");
    }
}