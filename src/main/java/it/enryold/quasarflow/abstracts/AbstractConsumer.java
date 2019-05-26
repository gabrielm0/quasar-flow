package it.enryold.quasarflow.abstracts;


import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.strands.Strand;
import co.paralleluniverse.strands.SuspendableAction2;
import co.paralleluniverse.strands.SuspendableRunnable;
import co.paralleluniverse.strands.channels.Channel;
import co.paralleluniverse.strands.channels.Channels;
import co.paralleluniverse.strands.channels.ReceivePort;
import co.paralleluniverse.strands.channels.SendPort;
import co.paralleluniverse.strands.channels.reactivestreams.ReactiveStreams;
import it.enryold.quasarflow.components.IAccumulator;
import it.enryold.quasarflow.components.IAccumulatorFactory;
import it.enryold.quasarflow.interfaces.*;
import it.enryold.quasarflow.interfaces.*;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;


public abstract class AbstractConsumer<E> implements IConsumer<E> {

    private final Logger log = LoggerFactory.getLogger(getClass());



    private int subscribersBuffer = 1_000_000;
    private int processorsBuffer = 1_000_000;
    private int dispatcherBuffer = 1_000_000;
    private Channels.OverflowPolicy subscriberOverflowPolicy = Channels.OverflowPolicy.BLOCK;
    private Channels.OverflowPolicy processorOverflowPolicy = Channels.OverflowPolicy.BLOCK;
    private Channels.OverflowPolicy dispatcherOverflowPolicy = Channels.OverflowPolicy.BLOCK;


    private List<Fiber<Void>> subscriberStrands = new ArrayList<>();
    private List<ReceivePort> processorChannels = new ArrayList<>();
    private Fiber<Void> dispatcherStrand;
    private IEmitter<E> emitter;
    private Channel<E>[] rrChannels;
    private String name;
    private IFlow flow;


    public AbstractConsumer(IFlow flow, IEmitter<E> eEmitter, String name){
        this.flow = flow;
        this.emitter = eEmitter;
        this.name = name == null ? String.valueOf(this.hashCode()) : name;
        flow.addStartable(this);
    }

    public AbstractConsumer(IFlow flow, IEmitter<E> eEmitter){
        this(flow, eEmitter, null);
    }


    public <I extends IConsumer<E>> I withSubscriberBuffer(int buffer){
        this.subscribersBuffer = buffer;
        return (I)this;
    }

    public <I extends IConsumer<E>> I withSubscriberOverflowPolicy(Channels.OverflowPolicy overflowPolicy){
        this.subscriberOverflowPolicy = overflowPolicy;
        return (I)this;
    }

    public <I extends IConsumer<E>> I withProcessorsBuffer(int buffer){
        this.processorsBuffer = buffer;
        return (I)this;
    }

    public <I extends IConsumer<E>> I withProcessorOverflowPolicy(Channels.OverflowPolicy overflowPolicy){
        this.processorOverflowPolicy = overflowPolicy;
        return (I)this;
    }



    @Override
    public void start() {
        subscriberStrands.forEach(f -> {
            System.out.println("Start RECEIVER "+name+" subscriber strand "+f.getName());
            f.start();
        });

        if(dispatcherStrand != null){
            System.out.println("Start RECEIVER "+name+" dispatcher strand "+dispatcherStrand.getName());
            dispatcherStrand.start();
        }else{
            System.out.println("Start RECEIVER "+name);
        }
    }

    @Override
    public IFlow flow() {
        return flow;
    }




    private ReceivePort<E> buildProcessor()
    {
        Publisher<E> publisher = emitter.getPublisher();

        Processor<E, E> processor = ReactiveStreams.toProcessor(processorsBuffer, processorOverflowPolicy, (SuspendableAction2<ReceivePort<E>, SendPort<E>>) (in, out) -> {
            for (; ; ) {
                E x = in.receive();
                if (x == null)
                    break;
                out.send(x);
            }
        });
        publisher.subscribe(processor);
        return ReactiveStreams.subscribe(subscribersBuffer, subscriberOverflowPolicy, processor);
    }


    private List<Publisher<E>> buildRRDispatcher(IEmitter<E> emitter, int workers)
    {
        this.emitter = emitter;

        rrChannels = IntStream.range(0, workers)
                .mapToObj(i -> Channels.<E>newChannel(dispatcherBuffer, dispatcherOverflowPolicy))
                .toArray((IntFunction<Channel<E>[]>) Channel[]::new);


        final ReceivePort<E> roundRobinSubscriberChannel = ReactiveStreams.subscribe(subscribersBuffer, subscriberOverflowPolicy, this.emitter.getPublisher());
        dispatcherStrand = new Fiber<>((SuspendableRunnable) () -> {

            int index = 0;


            for (; ; ) {
                E x = roundRobinSubscriberChannel.receive();
                if (x != null){
                    rrChannels[index++].send(x);
                    if (index == workers)
                        index = 0;
                }
            }
        });

        return Stream.of(rrChannels).map(ReactiveStreams::toPublisher).collect(Collectors.toList());

    }

    private ReceivePort<List<E>> buildProcessorWithSizeBatching(Publisher<E> publisher,
                                                                int chunkSize,
                                                                int flushTimeout,
                                                                TimeUnit flushTimeUnit)
    {

        final Processor<E, List<E>> processor = ReactiveStreams.toProcessor(processorsBuffer, processorOverflowPolicy, (SuspendableAction2<ReceivePort<E>, SendPort<List<E>>>) (in, out) -> {
            List<E> collection = new ArrayList<>();

            for(;;){
                E x;
                long deadline = System.nanoTime() + flushTimeUnit.toNanos(flushTimeout);


                do{
                    x = in.receive(1, TimeUnit.NANOSECONDS);

                    if (x == null) { // not enough elements immediately available; will have to poll
                        x = in.receive(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                        if (x == null) {
                            break; // we already waited enough, and there are no more elements in sight
                        }
                        collection.add(x);
                    }else{
                        collection.add(x);
                    }

                }while(collection.size() < chunkSize);

                if(collection.size() > 0){
                    out.send(new ArrayList<>(collection));
                }

                collection = new ArrayList<>();

            }


        });
        publisher.subscribe(processor);
        return ReactiveStreams.subscribe(subscribersBuffer, subscriberOverflowPolicy, processor);
    }



    private <T>ReceivePort<List<T>> buildProcessorWithByteBatching(Publisher<E> publisher,
                                                             IAccumulatorFactory<E, T> accumulatorFactory,
                                                                   int flushTimeout,
                                                                   TimeUnit flushTimeUnit)
    {

        final Processor<E, List<T>> processor = ReactiveStreams.toProcessor(processorsBuffer, processorOverflowPolicy, (SuspendableAction2<ReceivePort<E>, SendPort<List<T>>>) (in, out) -> {

            IAccumulator<E, T> accumulator = accumulatorFactory.build();


            for(;;){

                long deadline = System.nanoTime() + flushTimeUnit.toNanos(flushTimeout);
                boolean isAccumulatorAvailable;
                E elm;

                do{
                    elm = in.receive(1, TimeUnit.NANOSECONDS);

                    if (elm == null) { // not enough elements immediately available; will have to poll
                        elm = in.receive(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                        if (elm == null) {
                            break; // we already waited enough, and there are no more elements in sight
                        }
                        isAccumulatorAvailable = accumulator.add(elm);
                    }else{
                        isAccumulatorAvailable = accumulator.add(elm);
                    }
                }
                while (isAccumulatorAvailable);

                if(accumulator.getRecords().size() > 0){
                    out.send(new ArrayList<>(accumulator.getRecords()));
                }

                accumulator = accumulatorFactory.build();
                if(elm != null){ accumulator.add(elm); }
            }



        });
        publisher.subscribe(processor);
        return ReactiveStreams.subscribe(subscribersBuffer, subscriberOverflowPolicy, processor);

    }

    private <I>Fiber<Void> subscribeFiber(ReceivePort<I> channel, IConsumerTask<I> ingestionTask)
    {
        return new Fiber<>((SuspendableRunnable) () -> {
            for (; ; ) {
                try{
                    I x = channel.receive();
                    if (x == null)
                        break;

                    ingestionTask.ingest(x);

                }
                catch (InterruptedException e){
                    log.debug("Strand interrupted: "+Strand.currentStrand().getName());
                }
                catch (Exception e){
                    log.error("Strand in Exception: "+Strand.currentStrand().getName()+" - Message: "+e.getMessage());
                    e.printStackTrace();
                }

            }
        });
    }


    private <T>void registerProcessorChannel(ReceivePort<T> receivePort){
        processorChannels.add(receivePort);
    }

    @Override
    public IFlow consume(IConsumerTask<E> task) {

        ReceivePort<E> processor = buildProcessor();
        this.registerProcessorChannel(processor);
        subscriberStrands.add(subscribeFiber(processor, task));
        return this.flow;
    }


    @Override
    public IFlow consumeWithSizeBatching(int chunkSize, int flushTimeout, TimeUnit flushTimeUnit, IConsumerTask<List<E>> task){

        ReceivePort<List<E>> processor = buildProcessorWithSizeBatching(emitter.getPublisher(), chunkSize, flushTimeout, flushTimeUnit);
        this.registerProcessorChannel(processor);
        subscriberStrands.add(subscribeFiber(processor, task));
        return this.flow;

    }

    @Override
    public <T>IFlow consumeWithByteBatching(
            IAccumulatorFactory<E, T> accumulatorFactory,
            int flushTimeout,
            TimeUnit flushTimeUnit,
            IConsumerTask<List<T>> task){

        ReceivePort<List<T>> processor = buildProcessorWithByteBatching(emitter.getPublisher(), accumulatorFactory, flushTimeout, flushTimeUnit);
        this.registerProcessorChannel(processor);
        subscriberStrands.add(subscribeFiber(processor, task));
        return this.flow;
    }


    @Override
    public IFlow consumeWithFanOutAndSizeBatching(int workers,
                                                  int chunkSize,
                                                  int flushTimeout,
                                                  TimeUnit flushTimeUnit,
                                                  IConsumerTaskFactory<List<E>> taskFactory){

        buildRRDispatcher(emitter, workers)
                .stream()
                .map(p -> buildProcessorWithSizeBatching(p, chunkSize, flushTimeout, flushTimeUnit))
                .peek(this::registerProcessorChannel)
                .forEach(c -> subscriberStrands.add(subscribeFiber(c, taskFactory.build())));

        return this.flow;

    }

    @Override
    public <T>IFlow consumeWithFanOutAndByteBatching(int workers,
                                                     IAccumulatorFactory<E, T> accumulatorFactory,
                                                     int flushTimeout,
                                                     TimeUnit flushTimeUnit,
                                                     IConsumerTaskFactory<List<T>> taskFactory){

        buildRRDispatcher(emitter, workers)
                .stream()
                .map(p -> buildProcessorWithByteBatching(p, accumulatorFactory, flushTimeout, flushTimeUnit))
                .peek(this::registerProcessorChannel)
                .forEach(c -> subscriberStrands.add(subscribeFiber(c, taskFactory.build())));

        return this.flow;
    }



    @Override
    public String toString() {
        return "RECEIVER: "+((name == null) ? this.hashCode() : name);
    }

    @Override
    public void destroy() {
        subscriberStrands.stream().filter(Fiber::isAlive).forEach(s -> s.cancel(true));
        processorChannels.forEach(ReceivePort::close);
    }
}
