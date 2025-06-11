package com.krickert.yappy.modules.embedder;

import ai.djl.Device;
import ai.djl.MalformedModelException;
import ai.djl.engine.Engine;
import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import io.micronaut.context.annotation.EachBean;
import io.micronaut.core.annotation.Nullable;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Implementation of the Vectorizer interface using DJL (Deep Java Library).
 * This class manages a pool of predictors for efficient embedding generation.
 */
@Singleton
@EachBean(VectorizerConfig.class)
public class DJLVectorizer implements Vectorizer {

    private static final Logger log = LoggerFactory.getLogger(DJLVectorizer.class);
    private static final int MAX_BATCH_SIZE = 64; // Maximum batch size per predictor

    private final String modelId;
    private final String modelUrl;
    private final ZooModel<String, float[]> model;
    private final BlockingQueue<Predictor<String, float[]>> predictorPool;
    private final int poolSize;

    /**
     * Creates a new DJLVectorizer with the specified configuration.
     *
     * @param config the vectorizer configuration
     * @throws ModelNotFoundException if the model cannot be found
     * @throws MalformedModelException if the model is malformed
     * @throws IOException if an I/O error occurs
     */
    public DJLVectorizer(VectorizerConfig config) throws ModelNotFoundException, MalformedModelException, IOException {
        this.modelId = config.getModel().name();
        this.modelUrl = config.getModel().getUri();
        this.poolSize = config.getPoolSize();
        
        log.info("Loading model {} from {}", modelId, modelUrl);
        this.model = loadModel(modelUrl);
        this.predictorPool = createPredictorPool();
        log.info("Model {} loaded successfully", modelId);
    }

    private ZooModel<String, float[]> loadModel(String modelUrl) throws ModelNotFoundException, MalformedModelException, IOException {
        Device device = selectDevice();

        Criteria<String, float[]> criteria = Criteria.builder()
                .setTypes(String.class, float[].class)
                .optModelUrls(modelUrl)
                .optEngine("PyTorch")
                .optDevice(device)
                .optTranslatorFactory(new TextEmbeddingTranslatorFactory())
                .build();

        return criteria.loadModel();
    }

    private Device selectDevice() {
        // Check for CUDA GPUs
        int gpuCount = Engine.getInstance().getGpuCount();
        if (gpuCount > 0) {
            Device device = Device.gpu();
            log.info("Using CUDA GPU for inference");
            return device;
        }

        // Fallback to CPU
        Device device = Engine.getInstance().defaultDevice();
        log.info("No GPU found. Using default device for inference: {}", device);
        return device;
    }

    private BlockingQueue<Predictor<String, float[]>> createPredictorPool() {
        BlockingQueue<Predictor<String, float[]>> pool = new ArrayBlockingQueue<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            Predictor<String, float[]> predictor = model.newPredictor();
            pool.offer(predictor);
        }
        log.info("Initialized predictor pool with size {}", poolSize);
        return pool;
    }

    @Override
    public float[] embeddings(String text) {
        if (text == null || text.isEmpty()) {
            log.warn("Attempted to vectorize null or empty text");
            return new float[0];
        }

        log.debug("Vectorizing text '{}' using model '{}'", text, modelId);
        Predictor<String, float[]> predictor = null;
        try {
            predictor = predictorPool.take(); // Blocks if pool is empty
            float[] response = predictor.predict(text);
            log.debug("Text input returned embeddings of size [{}]", response.length);
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Thread was interrupted while waiting for a predictor from the pool", e);
        } catch (TranslateException e) {
            throw new RuntimeException("Error generating embeddings for text: " + text, e);
        } finally {
            if (predictor != null) {
                predictorPool.offer(predictor); // Return predictor to the pool
            }
        }
    }

    @Override
    public List<float[]> batchEmbeddings(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            log.warn("Attempted to vectorize null or empty batch");
            return Collections.emptyList();
        }

        log.debug("Vectorizing batch of {} texts using model '{}'", texts.size(), modelId);

        // Preallocate an array to hold embeddings
        float[][] embeddings = new float[texts.size()][];

        // Define a Batch class to hold the batch data and its start index
        class Batch {
            List<String> batchTexts;
            int startIndex;

            Batch(List<String> batchTexts, int startIndex) {
                this.batchTexts = batchTexts;
                this.startIndex = startIndex;
            }
        }

        // Divide the texts into batches and keep track of their start indices
        List<Batch> batches = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, texts.size());
            List<String> batchTexts = texts.subList(i, end);
            batches.add(new Batch(batchTexts, i));
        }

        // Use an ExecutorService with a fixed thread pool size equal to the predictor pool size
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);

        // Semaphore to limit the number of concurrent tasks to the pool size
        Semaphore semaphore = new Semaphore(poolSize);

        List<Future<?>> futures = new ArrayList<>();

        for (Batch batch : batches) {
            try {
                semaphore.acquire(); // Acquire a permit before processing the batch
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Thread was interrupted while acquiring semaphore permit", e);
            }

            // Submit each batch processing as a runnable task
            Future<?> future = executor.submit(() -> {
                Predictor<String, float[]> predictor = null;
                try {
                    predictor = predictorPool.take(); // Blocks if pool is empty
                    List<float[]> responses = predictor.batchPredict(batch.batchTexts);
                    log.debug("Batch of size {} returned embeddings", batch.batchTexts.size());

                    // Place embeddings in the correct positions
                    for (int j = 0; j < responses.size(); j++) {
                        embeddings[batch.startIndex + j] = responses.get(j);
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Thread was interrupted while waiting for a predictor from the pool", e);
                } catch (TranslateException e) {
                    throw new RuntimeException("Error during batch prediction", e);
                } finally {
                    if (predictor != null) {
                        predictorPool.offer(predictor); // Return predictor to the pool
                    }
                    semaphore.release(); // Release the permit after processing
                }
            });
            futures.add(future);
        }

        // Wait for all tasks to complete
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Thread was interrupted while waiting for batch embeddings", e);
            } catch (ExecutionException e) {
                throw new RuntimeException("Error during batch embeddings computation", e.getCause());
            }
        }

        executor.shutdown();

        // Convert the embeddings array to a list and return
        return Arrays.asList(embeddings);
    }

    @Override
    public Collection<Float> getEmbeddings(String text) {
        float[] embeddings = embeddings(text);
        if (embeddings.length == 0) {
            return Collections.emptyList();
        }
        
        List<Float> embeddingList = new ArrayList<>(embeddings.length);
        for (float value : embeddings) {
            embeddingList.add(value);
        }
        return embeddingList;
    }
    
    @Override
    public String getModelId() {
        return modelId;
    }

    @PreDestroy
    public void close() {
        if (model != null) {
            // Close all predictors in the pool
            predictorPool.forEach(Predictor::close);
            predictorPool.clear();

            // Close the model
            model.close();
            log.info("Model and predictors closed successfully: {}", modelId);
        }
    }
}