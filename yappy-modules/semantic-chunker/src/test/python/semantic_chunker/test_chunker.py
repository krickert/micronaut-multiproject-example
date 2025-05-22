"""
Tests for the semantic chunker implementation.
"""

import unittest
import pytest
from semantic_chunker.chunker import SemanticChunker

class TestSemanticChunker(unittest.TestCase):
    """Test cases for the SemanticChunker class."""
    
    def setUp(self):
        """Set up a chunker instance for testing."""
        self.chunker = SemanticChunker(
            model_name="all-MiniLM-L6-v2",
            chunk_size=100,
            chunk_overlap=20,
            similarity_threshold=0.75
        )
        
        # Sample text for testing
        self.sample_text = """
        This is a sample text for testing the semantic chunker. 
        It contains multiple sentences that should be split into chunks.
        The chunker should preserve sentence boundaries when possible.
        If a sentence is too long, it should be split at word boundaries.
        The chunks should overlap by the specified amount.
        """
    
    def test_chunk_text(self):
        """Test that text is correctly chunked."""
        chunks = self.chunker.chunk_text(self.sample_text)
        
        # Check that we have chunks
        self.assertTrue(len(chunks) > 0)
        
        # Check that each chunk is not longer than chunk_size
        for chunk in chunks:
            self.assertLessEqual(len(chunk), self.chunker.chunk_size)
            
        # Check that the chunks contain the original text
        combined_text = " ".join(chunks)
        for word in self.sample_text.split():
            self.assertIn(word, combined_text)
    
    def test_create_embeddings(self):
        """Test that embeddings are created correctly."""
        chunks = self.chunker.chunk_text(self.sample_text)
        embeddings = self.chunker.create_embeddings(chunks)
        
        # Check that we have an embedding for each chunk
        self.assertEqual(len(chunks), len(embeddings))
        
        # Check that each embedding is a list of floats
        for embedding in embeddings:
            self.assertIsInstance(embedding, list)
            self.assertTrue(all(isinstance(value, float) for value in embedding))
            
        # Check that all embeddings have the same dimension
        embedding_dim = len(embeddings[0])
        for embedding in embeddings:
            self.assertEqual(len(embedding), embedding_dim)
    
    def test_process_text(self):
        """Test the complete text processing pipeline."""
        result = self.chunker.process_text(self.sample_text)
        
        # Check that the result contains chunks and chunk_groups
        self.assertIn("chunks", result)
        self.assertIn("chunk_groups", result)
        
        # Check that each chunk has the required fields
        for chunk in result["chunks"]:
            self.assertIn("chunk_id", chunk)
            self.assertIn("text_content", chunk)
            self.assertIn("vector", chunk)
            self.assertIn("original_char_start_offset", chunk)
            self.assertIn("original_char_end_offset", chunk)
            
        # Check that chunk groups have the required fields
        for group in result["chunk_groups"]:
            self.assertIn("chunk_ids", group)
            self.assertIn("text_content", group)
            self.assertIn("vector", group)
            self.assertIn("chunk_group_id", group)
    
    def test_custom_config(self):
        """Test that custom configuration is applied correctly."""
        custom_config = {
            "chunk_size": 50,
            "chunk_overlap": 10,
            "similarity_threshold": 0.8
        }
        
        result = self.chunker.process_text(self.sample_text, custom_config)
        
        # Check that the chunker's settings were updated
        self.assertEqual(self.chunker.chunk_size, custom_config["chunk_size"])
        self.assertEqual(self.chunker.chunk_overlap, custom_config["chunk_overlap"])
        self.assertEqual(self.chunker.similarity_threshold, custom_config["similarity_threshold"])
        
        # Check that the result contains chunks
        self.assertIn("chunks", result)
        
        # Check that each chunk is not longer than the new chunk_size
        for chunk in result["chunks"]:
            self.assertLessEqual(len(chunk["text_content"]), custom_config["chunk_size"])

if __name__ == "__main__":
    unittest.main()