#!/bin/bash
cd /Users/krickert/IdeaProjects/yappy-work

# Create a simple Java program to verify embeddings
cat > VerifyEmbeddings.java << 'EOF'
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.SemanticProcessingResult;
import java.io.FileInputStream;

public class VerifyEmbeddings {
    public static void main(String[] args) throws Exception {
        String file = args[0];
        PipeDoc doc = PipeDoc.parseFrom(new FileInputStream(file));
        
        System.out.println("Document: " + doc.getId());
        System.out.println("Semantic Results: " + doc.getSemanticResultsCount());
        
        int totalEmbeddings = 0;
        for (SemanticProcessingResult result : doc.getSemanticResultsList()) {
            boolean hasEmbeddings = !result.getEmbeddingConfigId().isEmpty();
            if (hasEmbeddings) {
                totalEmbeddings += result.getChunksCount();
                System.out.println("  - " + result.getResultSetName() + ": " + 
                    result.getChunksCount() + " chunks with embeddings from " + 
                    result.getEmbeddingConfigId());
            }
        }
        System.out.println("Total chunks with embeddings: " + totalEmbeddings);
    }
}
EOF

# Compile and run
javac -cp "yappy-models/protobuf-models/build/libs/*" VerifyEmbeddings.java
java -cp ".:yappy-models/protobuf-models/build/libs/*" VerifyEmbeddings yappy-models/protobuf-models-test-data-resources/build/pipeline-test-data/embedder2-output/embedder2-output-000-doc-0276f000.bin

rm VerifyEmbeddings.java VerifyEmbeddings.class