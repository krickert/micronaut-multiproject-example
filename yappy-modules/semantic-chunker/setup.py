from setuptools import setup, find_packages

setup(
    name="semantic-chunker",
    version="1.0.0",
    packages=find_packages(where="src/main/python"),
    package_dir={"": "src/main/python"},
    install_requires=[
        "grpcio",
        "grpcio-tools",
        "protobuf",
        "transformers",
        "torch",
        "sentence-transformers",
        "nltk",
        "python-dotenv",
        "pyyaml",
    ],
    python_requires=">=3.8",
    description="A semantic chunker module for the YAPPY platform",
    author="YAPPY Team",
    author_email="yappy@example.com",
    classifiers=[
        "Development Status :: 3 - Alpha",
        "Intended Audience :: Developers",
        "Programming Language :: Python :: 3",
        "Programming Language :: Python :: 3.8",
        "Programming Language :: Python :: 3.9",
        "Programming Language :: Python :: 3.10",
    ],
)