package com.krickert.search.model.util;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Utility to clean up duplicate test data files.
 * Keeps only one file per document number, preferring the newest.
 */
public class TestDataCleanupUtility {
    
    // Pattern to match files like: tika_doc_000_doc-000-4812edc8.bin
    private static final Pattern FILE_PATTERN = Pattern.compile("^(.+?)_(\\d{3})_(.+?)\\.bin$");
    
    /**
     * Clean up duplicate files in a directory, keeping only one per document number.
     * 
     * @param directory The directory to clean
     * @param dryRun If true, only report what would be deleted without actually deleting
     * @return Number of files deleted
     */
    public static int cleanupDuplicates(Path directory, boolean dryRun) throws IOException {
        if (!Files.exists(directory)) {
            System.out.println("Directory does not exist: " + directory);
            return 0;
        }
        
        // Group files by document number
        Map<String, List<Path>> fileGroups = new HashMap<>();
        
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.bin")) {
            for (Path file : stream) {
                String filename = file.getFileName().toString();
                Matcher matcher = FILE_PATTERN.matcher(filename);
                
                if (matcher.matches()) {
                    String prefix = matcher.group(1);
                    String number = matcher.group(2);
                    String key = prefix + "_" + number;
                    
                    fileGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(file);
                }
            }
        }
        
        int deletedCount = 0;
        
        // Process each group
        for (Map.Entry<String, List<Path>> entry : fileGroups.entrySet()) {
            List<Path> files = entry.getValue();
            
            if (files.size() > 1) {
                // Sort by modification time (newest first)
                files.sort((a, b) -> {
                    try {
                        BasicFileAttributes attrA = Files.readAttributes(a, BasicFileAttributes.class);
                        BasicFileAttributes attrB = Files.readAttributes(b, BasicFileAttributes.class);
                        return attrB.lastModifiedTime().compareTo(attrA.lastModifiedTime());
                    } catch (IOException e) {
                        return 0;
                    }
                });
                
                System.out.println("\nDocument group: " + entry.getKey());
                System.out.println("  Found " + files.size() + " versions:");
                
                for (int i = 0; i < files.size(); i++) {
                    Path file = files.get(i);
                    String status = (i == 0) ? "KEEP" : "DELETE";
                    
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                        System.out.printf("    [%s] %s (modified: %s, size: %d bytes)%n",
                            status,
                            file.getFileName(),
                            attrs.lastModifiedTime(),
                            attrs.size());
                        
                        if (i > 0 && !dryRun) {
                            Files.delete(file);
                            deletedCount++;
                        } else if (i > 0 && dryRun) {
                            deletedCount++;
                        }
                    } catch (IOException e) {
                        System.err.println("    Error processing file: " + file + " - " + e.getMessage());
                    }
                }
            }
        }
        
        System.out.println("\n=== Cleanup Summary ===");
        System.out.println("Total document groups: " + fileGroups.size());
        System.out.println("Groups with duplicates: " + 
            fileGroups.values().stream().filter(list -> list.size() > 1).count());
        System.out.println("Files " + (dryRun ? "would be deleted" : "deleted") + ": " + deletedCount);
        
        return deletedCount;
    }
    
    /**
     * Main method for command-line usage.
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: TestDataCleanupUtility <directory> [--dry-run]");
            System.exit(1);
        }
        
        Path directory = Paths.get(args[0]);
        boolean dryRun = args.length > 1 && "--dry-run".equals(args[1]);
        
        if (dryRun) {
            System.out.println("=== DRY RUN MODE - No files will be deleted ===\n");
        }
        
        cleanupDuplicates(directory, dryRun);
    }
}