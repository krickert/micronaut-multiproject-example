public class SimpleVectorSetDemo {
    public static void main(String[] args) {
        System.out.println("=== Vector Set Calculation Demo ===");
        
        int numberOfChunks = 2;
        int numberOfEmbeddingModels = 3;
        int totalVectorSets = numberOfChunks * numberOfEmbeddingModels;
        
        System.out.println("Number of chunks per document: " + numberOfChunks);
        System.out.println("Number of embedding models: " + numberOfEmbeddingModels);
        System.out.println("Total vector sets: " + totalVectorSets + " (chunks × embeddings)");
        
        System.out.println("\n=== Expected Vector Sets ===");
        String[] embeddingModels = {"ALL_MINILM_L6_V2", "PARAPHRASE_MULTILINGUAL_MINILM_L12_V2", "THIRD_EMBEDDING_MODEL"};
        
        for (int chunkIndex = 0; chunkIndex < numberOfChunks; chunkIndex++) {
            System.out.println("\nChunk " + chunkIndex + " vectors:");
            for (int modelIndex = 0; modelIndex < numberOfEmbeddingModels; modelIndex++) {
                int vectorSetNumber = (chunkIndex * numberOfEmbeddingModels) + modelIndex + 1;
                System.out.println("  Vector Set " + vectorSetNumber + ": Chunk " + chunkIndex + 
                    " embedded with " + embeddingModels[modelIndex]);
            }
        }
        
        System.out.println("\n=== Summary ===");
        System.out.println("Total: " + totalVectorSets + " vector sets for a document with " + 
            numberOfChunks + " chunks and " + numberOfEmbeddingModels + " embedding models");
        
        System.out.println("\nNote: Documents without a body field would produce 0 vector sets");
        System.out.println("Memory recommendation: With large embedding models, allocate 10GB heap (-Xmx10g)");
    }
}