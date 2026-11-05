package com.x7x24.execution;
import java.util.function.Supplier;
/** Rechecked at actual read/model boundaries, not merely when the attempt was created. */
public final class ExecutionGuard {
 private ExecutionGuard(){}private static final ThreadLocal<Runnable> CURRENT=new ThreadLocal<>();
 public static boolean active(){return CURRENT.get()!=null;}
 public static void check(){var guard=CURRENT.get();if(guard!=null)guard.run();}
 public static <T>T with(Runnable guard,Supplier<T> action){var old=CURRENT.get();CURRENT.set(guard);try{check();return action.get();}finally{if(old==null)CURRENT.remove();else CURRENT.set(old);}}
}
