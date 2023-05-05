package com.chanzy.code;

import com.chanzy.ServerSLURM;
import com.chanzy.ServerSSH;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

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
            , REMOVE_DIR
            , RMDIR // 兼容旧版
            , MAKE_DIR
            , MKDIR // 兼容旧版
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
            , SLURM_CANCEL_ALL
            , CANCEL_ALL // 兼容旧版
            , SLURM_CANCEL_THIS
            , CANCEL_THIS // 兼容旧版
            , SLURM_SUBMIT_SYSTEM
            , SLURM_SUBMIT_BASH
            , SLURM_SUBMIT_SRUN
            , SLURM_SUBMIT_SRUN_BASH
        }
        public static Task fromString(final Object aTaskCreator, String aStr) {
            Pair<String, List<String>> tPair = getKeyValue_(aStr);
            Type tKey = Type.valueOf(tPair.first);
            String[] tValue = tPair.second.toArray(new String[0]);
            switch (tKey) {
            case MERGE:
                return mergeTask(fromString(aTaskCreator, tValue[0]), fromString(aTaskCreator, tValue[1]));
            case SLURM_CANCEL_ALL: case CANCEL_ALL:
            case SLURM_CANCEL_THIS: case CANCEL_THIS:
            case SLURM_SUBMIT_SYSTEM: case SLURM_SUBMIT_BASH: case SLURM_SUBMIT_SRUN: case SLURM_SUBMIT_SRUN_BASH:
                return fromString_(aTaskCreator, (aTaskCreator instanceof ServerSLURM) ? (ServerSLURM)aTaskCreator : null, tKey, tValue);
            case SYSTEM:
            case PUT_DIR:     case GET_DIR:     case CLEAR_DIR:
            case PUT_DIR_PAR: case GET_DIR_PAR: case CLEAR_DIR_PAR:
            case REMOVE_DIR:  case RMDIR:
            case MAKE_DIR:    case MKDIR:
            case PUT_FILE:    case GET_FILE:
            case PUT_WORKING_DIR:   case PUT_WORKING_DIR_PAR:
            case GET_WORKING_DIR:   case GET_WORKING_DIR_PAR:
            case CLEAR_WORKING_DIR: case CLEAR_WORKING_DIR_PAR:
                return fromString_(aTaskCreator, (aTaskCreator instanceof ServerSLURM) ? ((ServerSLURM)aTaskCreator).ssh() : (aTaskCreator instanceof ServerSSH) ? (ServerSSH)aTaskCreator : null, tKey, tValue);
            case NULL: default:
                return null;
            }
        }
        
        static Task fromString_(final Object aTaskCreator, ServerSLURM aSLURM, Type aKey, String... aValues) {
            if (aSLURM == null) return null;
            switch (aKey) {
            case SLURM_CANCEL_ALL: case CANCEL_ALL:
                return aSLURM.task_cancelAll();
            case SLURM_CANCEL_THIS: case CANCEL_THIS:
                return aSLURM.task_cancelThis();
            case SLURM_SUBMIT_SYSTEM:
                return aSLURM.task_submitSystem     (fromString(aTaskCreator, aValues[0]), fromString(aTaskCreator, aValues[1]), aValues[2], aValues[3], Integer.parseInt(aValues[4]), aValues[5]);
            case SLURM_SUBMIT_BASH:
                return aSLURM.task_submitBash       (fromString(aTaskCreator, aValues[0]), fromString(aTaskCreator, aValues[1]), aValues[2], aValues[3], Integer.parseInt(aValues[4]), aValues[5]);
            case SLURM_SUBMIT_SRUN:
                return aSLURM.task_submitSrun       (fromString(aTaskCreator, aValues[0]), fromString(aTaskCreator, aValues[1]), aValues[2], aValues[3], Integer.parseInt(aValues[4]), Integer.parseInt(aValues[5]), aValues[6]);
            case SLURM_SUBMIT_SRUN_BASH:
                return aSLURM.task_submitSrunBash   (fromString(aTaskCreator, aValues[0]), fromString(aTaskCreator, aValues[1]), aValues[2], aValues[3], Integer.parseInt(aValues[4]), Integer.parseInt(aValues[5]), aValues[6]);
            default:
                return null;
            }
        }
        
        static Task fromString_(final Object aTaskCreator, ServerSSH aSSH, Type aKey, String... aValues) {
            if (aSSH == null) return null;
            switch (aKey) {
            case SYSTEM:
                return aSSH.task_system         (aValues[0]);
            case PUT_DIR:
                return aSSH.task_putDir         (aValues[0]);
            case GET_DIR:
                return aSSH.task_getDir         (aValues[0]);
            case CLEAR_DIR:
                return aSSH.task_clearDir       (aValues[0]);
            case REMOVE_DIR: case RMDIR:
                return aSSH.task_rmdir          (aValues[0]);
            case MAKE_DIR: case MKDIR:
                return aSSH.task_mkdir          (aValues[0]);
            case PUT_FILE:
                return aSSH.task_putFile        (aValues[0]);
            case GET_FILE:
                return aSSH.task_getFile        (aValues[0]);
            case PUT_DIR_PAR:
                return aSSH.task_putDir         (aValues[0], Integer.parseInt(aValues[1]));
            case GET_DIR_PAR:
                return aSSH.task_getDir         (aValues[0], Integer.parseInt(aValues[1]));
            case CLEAR_DIR_PAR:
                return aSSH.task_clearDir       (aValues[0], Integer.parseInt(aValues[1]));
            case PUT_WORKING_DIR:
                return aSSH.task_putWorkingDir  ();
            case PUT_WORKING_DIR_PAR:
                return aSSH.task_putWorkingDir  (Integer.parseInt(aValues[0]));
            case GET_WORKING_DIR:
                return aSSH.task_getWorkingDir  ();
            case GET_WORKING_DIR_PAR:
                return aSSH.task_getWorkingDir  (Integer.parseInt(aValues[0]));
            case CLEAR_WORKING_DIR:
                return aSSH.task_clearWorkingDir();
            case CLEAR_WORKING_DIR_PAR:
                return aSSH.task_clearWorkingDir(Integer.parseInt(aValues[0]));
            default:
                return null;
            }
        }
        
        static final List<String> ZL_STR = new ArrayList<>();
        // deserialize the String in formation "Key{value1:value2:...}"
        static Pair<String, List<String>> getKeyValue_(String aStr) {
            int tValueIdx = aStr.indexOf("{");
            if (tValueIdx < 0) return new Pair<>(aStr, ZL_STR);
            String tKey = aStr.substring(0, tValueIdx);
            List<String> tValues = new ArrayList<>();
            // 直接遍历查找，注意在括号内部时不需要分割 :
            ++tValueIdx;
            int tIdx = tValueIdx;
            int tBlockCounter = 1;
            while (tBlockCounter > 0 && tIdx < aStr.length()) {
                if (aStr.charAt(tIdx)=='{') ++tBlockCounter;
                if (aStr.charAt(tIdx)=='}') --tBlockCounter;
                if (tBlockCounter == 1 && aStr.charAt(tIdx)==':') {
                    // 如果是 "null" 字符串则认为是 null
                    String tValue = aStr.substring(tValueIdx, tIdx);
                    if (tValue.equals("null")) tValue = null;
                    tValues.add(tValue);
                    tValueIdx = tIdx + 1;
                }
                if (tBlockCounter == 0) tValues.add(aStr.substring(tValueIdx, tIdx)); // 到达最后，最后一项放入 value
                ++tIdx;
            }
            return new Pair<>(tKey, tValues);
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
    
    /**
     * @author CHanzy
     * use Runtime.exec() to get the working dir
     * it seems like the only way to get the correct working dir in matlab
     * return `System.getProperty("user.home")` if failed in exec
     */
    public static String pwd() {
        String wd;
        try {
            Process tProcess = Runtime.getRuntime().exec(System.getProperty("os.name").toLowerCase().contains("windows") ? "cmd /c cd" : "pwd");
            tProcess.waitFor();
            BufferedReader tReader = new BufferedReader(new InputStreamReader(tProcess.getInputStream()));
            wd = tReader.readLine().trim();
        } catch (IOException | InterruptedException e) {
            wd = System.getProperty("user.home");
        }
        return wd;
    }
    
    /**
     * @author CHanzy
     * check whether the two paths are actually same
     * note that `toAbsolutePath` in `Paths` will still not work even used `setProperty`
     * so I implemented another kind of `toAbsolutePath`
     */
    public static boolean samePath(String aPath1, String aPath2) {
        return WORKING_PATH.resolve(aPath1).normalize().equals(WORKING_PATH.resolve(aPath2).normalize());
    }
    
    /**
     * @author CHanzy
     * right `toAbsolutePath` method
     */
    public static String toAbsolutePath(String aPath) {
        return WORKING_PATH.resolve(aPath).toString();
    }
    
    // reset the working dir to correct value
    private static Path WORKING_PATH;
    private static boolean INITIALIZED = false;
    public static void init() {
        if (INITIALIZED) return;
        INITIALIZED = true;
        String wd = pwd();
        System.setProperty("user.dir", wd);
        WORKING_PATH = Paths.get(wd);
    }
    
    static {init();}
}
