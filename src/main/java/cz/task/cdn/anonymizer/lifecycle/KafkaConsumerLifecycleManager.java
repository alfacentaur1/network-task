package cz.task.cdn.anonymizer.lifecycle;

import cz.task.cdn.anonymizer.service.AnonymizerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
//class for handling graceful shutdown of kafka consumer
public class KafkaConsumerLifecycleManager implements SmartLifecycle {

    private final AnonymizerService anonymizerService;
    private boolean isRunning = false;

    //flag to true - everything runs
    @Override
    public void start() {
        log.info("Kafka lifecycle manager started.");
        isRunning = true;
    }

    @Override
    public void stop() {
        //not used, we rely on stop(Runnable) for graceful shutdown
    }

    //flag to false - stop consuming, process remaining batch, if fails, log and rely on kafka re-delivery
    //param callback - instance of spring's LifecycleCallback
    @Override
    public void stop(Runnable callback) {
        log.info("Graceful shutdown initiated: Attempting to save remaining records...");
        try {
            anonymizerService.processBatch();
        } catch (Exception e) {
            log.warn("Final batch save failed due to proxy limits. Kafka will re-deliver messages upon restart.");
        }finally{
            isRunning = false;
            //tell spring we are done with shutdown, it can proceed with stopping the application
            callback.run();
        }

    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    //max value for stopping after kafka, kafka has maxvalue -100, we want to stop after that, so we use maxvalue - 200 to be safe
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 200;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}