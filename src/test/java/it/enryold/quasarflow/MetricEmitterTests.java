package it.enryold.quasarflow;


import it.enryold.quasarflow.interfaces.*;
import it.enryold.quasarflow.io.http.clients.okhttp.models.OkHttpRequest;
import it.enryold.quasarflow.models.utils.QRoutingKey;
import it.enryold.quasarflow.models.utils.QSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class MetricEmitterTests extends TestUtils {


    @AfterEach
    public void afterEach(){
        this.printRuntime();


      
    }




    @Test
    public void testMetric() throws InterruptedException {

        // PARAMS
        int elements = 19;

        // EMITTER
        IEmitterTask<String> stringEmitter = stringsEmitterTask(elements);


        IFlow currentFlow;


        currentFlow = QuasarFlow.newFlow(QSettings.test())
                .broadcastEmitter(stringEmitter, "stringEmitter")
                .addProcessor("stringProcessor")
                .process()
                .addConsumer("stringConsumer")
                .flow();

        currentFlow.print();


        System.out.println("-----------------\n");


        currentFlow = QuasarFlow.newFlow(QSettings.test())
                .broadcastEmitter(stringEmitter, "stringEmitter")
                .addProcessor("stringProcessor1", p -> p.process().addConsumer("c1").consume(e -> {}))
                .addProcessor("stringProcessor2", p -> p.process().addConsumer("c2").consume(e -> {}))
                .flow();

        currentFlow.print();


        System.out.println("-----------------\n");


        currentFlow = QuasarFlow.newFlow(QSettings.test())
                .broadcastEmitter(stringEmitter, "stringEmitter")
                .addProcessor("stringProcessor1", p -> p.process().addConsumer("c1").consume(e -> {}))
                .addProcessor("stringProcessor2", p -> p.process().addConsumer("c2").consume(e -> {}))
                .addProcessor("stringToIntProcessor")
                .process(() -> String::length)
                .map(emitter -> emitter
                        .addProcessor("intToStringProcessor")
                        .process(() -> integer -> "Length is: "+integer))
                .flow();

        currentFlow.print();


        System.out.println("-----------------\n");


        currentFlow = QuasarFlow.newFlow(QSettings.test())
                .broadcastEmitter(stringEmitter, "stringEmitter")
                .addProcessor("stringProcessor1",
                        p -> p
                                .process()
                                .addConsumer("c1")
                                .consume(e -> {}))
                .addProcessor("stringProcessor2",
                        p -> p
                                .process()
                                .addConsumer("c2")
                                .consume(e -> {}))
                .addProcessor("stringProcessor3",
                        p -> p
                                .process(() -> String::length)
                                .broadcast()
                                .addProcessor("intProcessor1",
                                    p1 -> p1
                                            .process()
                                            .addConsumer("cInt1")
                                            .consume(e -> {}))
                                .addProcessor("intProcessor2",
                                    p2 -> p2
                                            .process()
                                            .addConsumer("cInt2")
                                            .consume(e -> {})))
                .addProcessor("stringProcessor4",
                        p -> p
                                .process()
                                .routed(o -> QRoutingKey.withKey(o.charAt(6)+""))
                                .addProcessor("processor0", QRoutingKey.withKey("0"), p1 -> p1.process().addConsumer("c2").consume(e -> {}))
                                .addProcessor("processor1", QRoutingKey.withKey("1"), p1 -> p1.process().addConsumer("c2").consume(e -> {})))
                .map(emitter ->
                        emitter
                        .addProcessor("intProcessorMapped").process(() -> String::length)
                )


                .flow();

        currentFlow.start();

        Thread.sleep(1000);

        currentFlow.printMetrics();


    }



}
