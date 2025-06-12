package com.krickert.search.model.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Generates deterministic IDs for test data based on content or index.
 * This ensures that the same input always produces the same ID.
 */
public class DeterministicIdGenerator {
    
    /**
     * Generate a deterministic ID based on content hash.
     * 
     * @param content The content to hash
     * @return A deterministic ID string
     */
    public static String generateFromContent(String content) {
        if (content == null || content.isEmpty()) {
            return generateFromIndex(0);
        }
        
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            
            // Convert first 8 bytes of hash to hex string
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8 && i < hash.length; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // Fallback to UUID if SHA-256 is not available
            return UUID.nameUUIDFromBytes(content.getBytes(StandardCharsets.UTF_8))
                    .toString().substring(0, 8);
        }
    }
    
    /**
     * Generate a deterministic ID based on index.
     * 
     * @param index The index value
     * @return A deterministic ID string
     */
    public static String generateFromIndex(int index) {
        // Create a predictable ID based on index
        return String.format("%08x", index);
    }
    
    /**
     * Generate a deterministic ID based on multiple factors.
     * 
     * @param prefix A prefix for the ID
     * @param index The index value
     * @param content Optional content to include in hash
     * @return A deterministic ID string
     */
    public static String generateComposite(String prefix, int index, String content) {
        if (content != null && !content.isEmpty()) {
            // Use content hash if available
            String contentHash = generateFromContent(prefix + "-" + index + "-" + content);
            return contentHash.substring(0, 8);
        } else {
            // Use index-based ID
            return generateFromIndex(index);
        }
    }
    
    /**
     * Generate an ID based on configuration.
     * In deterministic mode, uses content/index-based generation.
     * Otherwise, uses random UUID.
     * 
     * @param prefix A prefix for the ID
     * @param index The index value
     * @param content Optional content to include in hash
     * @return An ID string
     */
    public static String generateId(String prefix, int index, String content) {
        if (TestDataGenerationConfig.isDeterministicMode()) {
            return generateComposite(prefix, index, content);
        } else {
            // Random mode - use UUID
            return UUID.randomUUID().toString().substring(0, 8);
        }
    }
}