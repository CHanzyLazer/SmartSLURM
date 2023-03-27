package com.chanzy.code;

import java.util.ArrayList;
import java.util.List;
import com.chanzy.ServerSLURM;
import com.chanzy.ServerSSH;

/**
 * @author CHanzy
 * utils of this project
 */
public class UT {
    /**
     * @author CHanzy
     * STL style pair
     * simple and more powerful
     */
    public static class Pair<A, B> {
        public A first;
        public B second;
        
        public Pair(A first, B second) {
            this.first = first;
            this.second = second;
        }
    }
    
    /**
     * @author CHanzy
     * similar to java Callable but use run and return boolean
     * must use this because matlab cant use interface (or even the Object of the interface)
     */
    public static class Task {
        public boolean run() throws Exception {return true;}
        // override to get serialized string
        @Override public String toString() {return Type.NULL.name();}
    
        // add to here to get task from String
        public enum Type {
              NULL
            , MERGE
            // SSH stuff
            , SYSTEM
            , PUT_DIR
            , GET_DIR
            , CLEAR_DIR
            , RMDIR
            , MKDIR
            , PUT_FILE
            , GET_FILE
            , PUT_DIR_PAR
            , GET_DIR_PAR
            , CLEAR_DIR_PAR
            , PUT_WORKING_DIR
            , PUT_WORKING_DIR_PAR
            , GET_WORKING_DIR
            , GET_WORKING_DIR_PAR
            , CLEAR_WORKING_DIR
            , CLEAR_WORKING_DIR_PAR
            // SLURM stuff
            , CANCEL_ALL
            , CANCEL_THIS
        }
        public static Task fromString(Object aTaskCreator, String aStr) {
            Pair<String, List<String>> tPair = getKeyValue_(aStr);
            switch (Type.valueOf(tPair.first)) {
            case MERGE:
                return mergeTask(fromString(aTaskCreator, tPair.second.get(0)), fromString(aTaskCreator, tPair.second.get(1)));
            case CANCEL_ALL:
                return (aTaskCreator instanceof ServerSLURM) ? ((ServerSLURM)aTaskCreator).task_cancelAll()  : null;
            case CANCEL_THIS:
                return (aTaskCreator instanceof ServerSLURM) ? ((ServerSLURM)aTaskCreator).task_cancelThis() : null;
            case SYSTEM:
                return (aTaskCreator instanceof ServerSLURM) ? ((ServerSLURM)aTaskCreator).ssh().task_system            (tPair.second.get(0)) : (aTaskCreator instanceof ServerSSH) ? ((ServerSSH)aTaskCreator).task_system     (tPair.second.get(0)) : null;
            case PUT_DIR:
                return (aTaskCreator instanceof ServerSLURM) ? ((ServerSLURM)aTaskCreator).ssh().task_putDir            (tPair.second.get(0)) : (aTaskCreator instanceof ServerSSH) ? ((ServerSSH)aTaskCreator).task_putDir     (tPair.second.get(0)) : null;
            case GET_DIR:
                return (aTaskCreator instanceof ServerSLURM) ? ((ServerSLURM)aTaskCreator).ssh().task_getDir            (tPair.second.get(0)) : (aTaskCreator instanceof ServerSSH) ? ((ServerSSH)aTaskCreator).task_getDir     (tPair.second.get(0)) : null;
            case CLEAR_DIR:
                return (aTaskCreator instanceof ServerSLURM) ? ((ServerSLURM)aTaskCreator).ssh().task_clearDir          (tPair.second.get(0)) : (aTaskCreator instanceof ServerSSH) ? ((ServerSSH)aTaskCreator).task_clearDir   (tPair.second.get(0)) : null;
            case RMDIR:
                return (aTaskCreator instanceof ServerSLURM) ? ((ServerSLURM)aTaskCreator).ssh().task_rmdir             (tPair.second.get(0)) : (aTaskCreator instanceof ServerSSH) ? ((ServerSSH)aTaskCreator).task_rmdir      (tPair.second.get(0)) : null;
            case MKDIR:
                return (aTaskCreator instanceof ServerSLURM) ? ((ServerSLURM)aTaskCreator).ssh().task_mkdir             (tPair.second.get(0)) : (aTaskCreator instanceof ServerSSH) ? ((ServerSSH)aTaskCreator).task_mkdir      (tPair.second.get(0)) : null;
            case PUT_FILE:
                return (aTaskCreator instanceof ServerSLURM) ? ((ServerSLURM)aTaskCreator).ssh().task_putFile           (tPair.second.get(0)) : (aTaskCreator instanceof ServerSSH) ? ((ServerSSH)aTaskCreator).task_putFile    (tPair.second.get(0)) : null;
            case GET_FILE:
                return (aTaskCreator instanceof ServerSLURM) ? ((ServerSLURM)aTaskCreator).ssh().task_getFile           (tPair.second.get(0)) : (aTaskCreator instanceof ServerSSH) ? ((ServerSSH)aTaskCreator).task_getFile    (tPair.second.get(0)) : null;
            case PUT_DIR_PAR:
                return (aTaskCreator instanceof ServerSLURM) ? ((ServerSLURM)aTaskCreator).ssh().task_putDir            (tPair.second.get(0), Integer.parseInt(tPair.second.get(1))) : (aTaskCreator instanceof ServerSSH) ? ((ServerSSH)aTaskCreator).task_putDir      (tPair.second.get(0), Integer.parseInt(tPair.second.get(1))) : null;
            case GET_DIR_PAR:
                return (aTaskCreator instanceof ServerSLURM) ? ((ServerSLURM)aTaskCreator).ssh().task_getDir            (tPair.second.get(0), Integer.parseInt(tPair.second.get(1))) : (aTaskCreator instanceof ServerSSH) ? ((ServerSSH)aTaskCreator).task_getDir      (tPair.second.get(0), Integer.parseInt(tPair.second.get(1))) : null;
            case CLEAR_DIR_PAR:
                return (aTaskCreator instanceof ServerSLURM) ? ((ServerSLURM)aTaskCreator).ssh().task_clearDir          (tPair.second.get(0), Integer.parseInt(tPair.second.get(1))) : (aTaskCreator instanceof ServerSSH) ? ((ServerSSH)aTaskCreator).task_clearDir    (tPair.second.get(0), Integer.parseInt(tPair.second.get(1))) : null;
            case PUT_WORKING_DIR:
                return (aTaskCreator instanceof ServerSLURM) ? ((ServerSLURM)aTaskCreator).ssh().task_putWorkingDir     () : (aTaskCreator instanceof ServerSSH) ? ((ServerSSH)aTaskCreator).task_putWorkingDir     () : null;
            case PUT_WORKING_DIR_PAR:
                return (aTaskCreator instanceof ServerSLURM) ? ((ServerSLURM)aTaskCreator).ssh().task_putWorkingDir     (Integer.parseInt(tPair.second.get(0))) : (aTaskCreator instanceof ServerSSH) ? ((ServerSSH)aTaskCreator).task_putWorkingDir     (Integer.parseInt(tPair.second.get(0))) : null;
            case GET_WORKING_DIR:
                return (aTaskCreator instanceof ServerSLURM) ? ((ServerSLURM)aTaskCreator).ssh().task_getWorkingDir     () : (aTaskCreator instanceof ServerSSH) ? ((ServerSSH)aTaskCreator).task_getWorkingDir     () : null;
            case GET_WORKING_DIR_PAR:
                return (aTaskCreator instanceof ServerSLURM) ? ((ServerSLURM)aTaskCreator).ssh().task_getWorkingDir     (Integer.parseInt(tPair.second.get(0))) : (aTaskCreator instanceof ServerSSH) ? ((ServerSSH)aTaskCreator).task_getWorkingDir     (Integer.parseInt(tPair.second.get(0))) : null;
            case CLEAR_WORKING_DIR:
                return (aTaskCreator instanceof ServerSLURM) ? ((ServerSLURM)aTaskCreator).ssh().task_clearWorkingDir   () : (aTaskCreator instanceof ServerSSH) ? ((ServerSSH)aTaskCreator).task_clearWorkingDir   () : null;
            case CLEAR_WORKING_DIR_PAR:
                return (aTaskCreator instanceof ServerSLURM) ? ((ServerSLURM)aTaskCreator).ssh().task_clearWorkingDir   (Integer.parseInt(tPair.second.get(0))) : (aTaskCreator instanceof ServerSSH) ? ((ServerSSH)aTaskCreator).task_clearWorkingDir   (Integer.parseInt(tPair.second.get(0))) : null;
            case NULL: default:
                return null;
            }
        }
        
        // deserialize the String in formation "Key{value1:value2:...}"
        static Pair<String, List<String>> getKeyValue_(String aStr) {
            int tValueIdx = aStr.indexOf("{");
            if (tValueIdx < 0) return new Pair<>(aStr, null);
            String tKey = aStr.substring(0, tValueIdx);
            List<String> tValue = new ArrayList<>();
            // 直接遍历查找，注意在括号内部时不需要分割 :
            ++tValueIdx;
            int tIdx = tValueIdx;
            int tBlockCounter = 1;
            while (tBlockCounter > 0 && tIdx < aStr.length()) {
                if (aStr.charAt(tIdx)=='{') ++tBlockCounter;
                if (aStr.charAt(tIdx)=='}') --tBlockCounter;
                if (tBlockCounter == 1 && aStr.charAt(tIdx)==':') {
                    tValue.add(aStr.substring(tValueIdx, tIdx));
                    tValueIdx = tIdx + 1;
                }
                if (tBlockCounter == 0) tValue.add(aStr.substring(tValueIdx, tIdx)); // 到达最后，最后一项放入 value
                ++tIdx;
            }
            return new Pair<>(tKey, tValue);
        }
    }
    
    
    /**
     * @author CHanzy
     * merge two tasks into one task
     */
    public static Task mergeTask(final Task aTask1, final Task aTask2) {
        if (aTask1 != null) {
            if (aTask2 == null) return aTask1;
            return new Task() {
                @Override public boolean run() throws Exception {return aTask1.run() && aTask2.run();}
                @Override public String toString() {return String.format("%s{%s:%s}", Type.MERGE.name(), aTask1, aTask2);}
            };
        }
        return aTask2;
    }
    
    /**
     * @author CHanzy
     * try to run a task
     * return true if it runs successfuly
     * return false otherwise
     */
    public static boolean tryTask(Task aTask) {
        if (aTask == null) return false;
        boolean tSuc;
        try {tSuc = aTask.run();} catch (Exception e) {return false;}
        return tSuc;
    }
    
    /**
     * @author CHanzy
     * try to run a task with tolerant
     * return true if it runs successfuly in tolerant
     * return false otherwise
     */
    public static boolean tryTask(Task aTask, int aTolerant) {
        if (aTask == null) return false;
        boolean tSuc = false;
        for (int i = 0; i < aTolerant; ++i) {
            try {tSuc = aTask.run();} catch (Exception e) {continue;}
            if (tSuc) break;
        }
        return tSuc;
    }
}
