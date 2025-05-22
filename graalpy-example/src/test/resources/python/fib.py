"""
Fibonacci module for GraalPy.

This module provides functions to calculate Fibonacci numbers.
"""

def calculate(position):
    """
    Calculate the Fibonacci number at a specific position.
    
    Args:
        position (int): The position in the Fibonacci sequence (0-based index)
        
    Returns:
        int: The Fibonacci number at the specified position
    """
    if position < 0:
        raise ValueError("Position must be a non-negative integer")
    
    if position <= 1:
        return position
    
    a, b = 0, 1
    for _ in range(2, position + 1):
        a, b = b, a + b
    
    return b

def sequence(length):
    """
    Generate a sequence of Fibonacci numbers up to a specified length.
    
    Args:
        length (int): The length of the sequence to generate
        
    Returns:
        list: A list of Fibonacci numbers
    """
    if length < 0:
        raise ValueError("Length must be a non-negative integer")
    
    result = []
    for i in range(length):
        result.append(calculate(i))
    
    return result