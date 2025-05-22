"""
Semantic chunker implementation.

This module provides the core functionality for semantic chunking of text.
"""

import nltk
import torch
from sentence_transformers import SentenceTransformer
from typing import List, Dict, Any, Tuple, Optional
import logging
import json

# Download necessary NLTK data
try:
    nltk.data.find('tokenizers/punkt')
except LookupError:
    nltk.download('punkt')

logger = logging.getLogger(__name__)

class SemanticChunker:
    """
    A class that provides semantic chunking functionality.
    
    This class uses sentence transformers to create semantic embeddings
    for text chunks and can group them based on semantic similarity.
    """
    
    def __init__(self, model_name: str = "all-MiniLM-L6-v2", 
                 chunk_size: int = 512, 
                 chunk_overlap: int = 50,
                 similarity_threshold: float = 0.75):
        """
        Initialize the semantic chunker.
        
        Args:
            model_name: The name of the sentence transformer model to use
            chunk_size: Maximum size of each chunk in characters
            chunk_overlap: Number of characters to overlap between chunks
            similarity_threshold: Threshold for considering chunks semantically similar
        """
        self.model_name = model_name
        self.chunk_size = chunk_size
        self.chunk_overlap = chunk_overlap
        self.similarity_threshold = similarity_threshold
        
        # Load the model
        logger.info(f"Loading sentence transformer model: {model_name}")
        self.model = SentenceTransformer(model_name)
        
    def chunk_text(self, text: str) -> List[str]:
        """
        Split text into chunks based on chunk_size and chunk_overlap.
        
        Args:
            text: The text to chunk
            
        Returns:
            A list of text chunks
        """
        if not text:
            return []
            
        # First split by sentences to preserve sentence boundaries
        sentences = nltk.sent_tokenize(text)
        
        chunks = []
        current_chunk = ""
        
        for sentence in sentences:
            # If adding this sentence would exceed chunk_size, 
            # save the current chunk and start a new one
            if len(current_chunk) + len(sentence) > self.chunk_size:
                if current_chunk:
                    chunks.append(current_chunk.strip())
                
                # If the sentence itself is longer than chunk_size,
                # we need to split it further
                if len(sentence) > self.chunk_size:
                    words = sentence.split()
                    current_chunk = ""
                    
                    for word in words:
                        if len(current_chunk) + len(word) + 1 > self.chunk_size:
                            chunks.append(current_chunk.strip())
                            current_chunk = word + " "
                        else:
                            current_chunk += word + " "
                else:
                    current_chunk = sentence + " "
            else:
                current_chunk += sentence + " "
        
        # Add the last chunk if it's not empty
        if current_chunk:
            chunks.append(current_chunk.strip())
            
        # Apply overlap
        if self.chunk_overlap > 0 and len(chunks) > 1:
            overlapped_chunks = []
            
            for i in range(len(chunks)):
                if i == 0:
                    overlapped_chunks.append(chunks[i])
                else:
                    # Get the end of the previous chunk for overlap
                    prev_chunk = chunks[i-1]
                    overlap_text = prev_chunk[-self.chunk_overlap:] if len(prev_chunk) > self.chunk_overlap else prev_chunk
                    
                    # Add the overlap to the beginning of the current chunk
                    overlapped_chunks.append(overlap_text + " " + chunks[i])
            
            chunks = overlapped_chunks
            
        return chunks
    
    def create_embeddings(self, chunks: List[str]) -> List[List[float]]:
        """
        Create embeddings for a list of text chunks.
        
        Args:
            chunks: List of text chunks
            
        Returns:
            List of embeddings (as lists of floats)
        """
        if not chunks:
            return []
            
        # Generate embeddings
        embeddings = self.model.encode(chunks)
        
        # Convert numpy arrays to lists for easier serialization
        return [embedding.tolist() for embedding in embeddings]
    
    def group_chunks_by_similarity(self, chunks: List[str], embeddings: List[List[float]]) -> List[Dict[str, Any]]:
        """
        Group chunks based on semantic similarity.
        
        Args:
            chunks: List of text chunks
            embeddings: List of embeddings for the chunks
            
        Returns:
            List of dictionaries containing chunk groups with their embeddings
        """
        if not chunks or not embeddings:
            return []
            
        # Convert embeddings back to tensors for similarity calculation
        embedding_tensors = [torch.tensor(emb) for emb in embeddings]
        
        # Initialize groups
        groups = []
        assigned = [False] * len(chunks)
        
        for i in range(len(chunks)):
            if assigned[i]:
                continue
                
            # Start a new group
            group = {
                "chunk_ids": [i],
                "text_content": chunks[i],
                "vector": embeddings[i],
                "chunk_group_id": f"group_{len(groups)}"
            }
            assigned[i] = True
            
            # Find similar chunks
            for j in range(i+1, len(chunks)):
                if assigned[j]:
                    continue
                    
                # Calculate cosine similarity
                similarity = torch.cosine_similarity(
                    embedding_tensors[i].unsqueeze(0),
                    embedding_tensors[j].unsqueeze(0)
                ).item()
                
                if similarity >= self.similarity_threshold:
                    group["chunk_ids"].append(j)
                    group["text_content"] += " " + chunks[j]
                    assigned[j] = True
            
            groups.append(group)
            
        return groups
    
    def process_text(self, text: str, config: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """
        Process text by chunking it and creating embeddings.
        
        Args:
            text: The text to process
            config: Optional configuration to override default settings
            
        Returns:
            Dictionary containing the processed chunks and their embeddings
        """
        if config:
            # Override default settings if provided in config
            chunk_size = config.get("chunk_size", self.chunk_size)
            chunk_overlap = config.get("chunk_overlap", self.chunk_overlap)
            similarity_threshold = config.get("similarity_threshold", self.similarity_threshold)
            model_name = config.get("model_name", self.model_name)
            
            # Update instance if model changed
            if model_name != self.model_name:
                self.model_name = model_name
                self.model = SentenceTransformer(model_name)
                
            self.chunk_size = chunk_size
            self.chunk_overlap = chunk_overlap
            self.similarity_threshold = similarity_threshold
        
        # Chunk the text
        chunks = self.chunk_text(text)
        
        # Create embeddings
        embeddings = self.create_embeddings(chunks)
        
        # Group chunks by similarity
        groups = self.group_chunks_by_similarity(chunks, embeddings)
        
        # Prepare result
        result = {
            "chunks": [],
            "chunk_groups": groups
        }
        
        for i, (chunk, embedding) in enumerate(zip(chunks, embeddings)):
            result["chunks"].append({
                "chunk_id": f"chunk_{i}",
                "text_content": chunk,
                "vector": embedding,
                "original_char_start_offset": text.find(chunk),
                "original_char_end_offset": text.find(chunk) + len(chunk)
            })
        
        return result