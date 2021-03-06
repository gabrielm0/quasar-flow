package it.enryold.quasarflow.interfaces;


import co.paralleluniverse.fibers.Suspendable;

@FunctionalInterface
public interface IConsumerTaskFactory<I> {

    @Suspendable
    IConsumerTask<I> build();
}
