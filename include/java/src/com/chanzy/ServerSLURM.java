package com.chanzy;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.chanzy.code.UT.*;

/**
 * @author CHanzy
 * 对于 SLURM 系统的服务器提供专门的接口方便任务提交
 * 与 SSH 不同，可以把本身当作一个 SystemThreadPool
 * 相比 SSH 多一个 mMaxJobNumber 参数限制同时运行的任务数
 */
public final class ServerSLURM {
    static final int DEFAULT_TOLERANT = 3;
    
    private final ServerSSH mSSH;
    private final int mMaxJobNumber;
    private final int mMaxThisJobNumber; // 可能会存在共用的现象，可以单限制此对象同时运行的任务数目
    private final String mSqueueName; // 有些 SLURM 系统 squeue 的用户名和登录用户名不同
    
    private final Map<Integer, Pair<Task, Integer>> mJobIDList = new LinkedHashMap<>(); // <JobID, <afterTask, tolerant>>
    private final LinkedList<Pair<Pair<Task, Task>, String>> mCommandList = new LinkedList<>(); // <<beforeTask, afterTask>, command>
    private final ExecutorService mPool;
    private boolean mDead = false;
    private boolean mPause = false; // 可以暂停任务的提交
    private boolean mKilled = false; // 直接强制杀死提交进程
    private boolean mSubmitting = false; // 记录是否正在提交
    private boolean mNeedSaveMirror = false;
    
    private long mSleepTime = 500; // ms 设置更高的值可以降低检测的频率
    // 各种提交任务的尝试次数类
    private final TolerantCounter mTolerantCounter = new TolerantCounter();
    // 保存提交的任务名称，不一定和真实的类名匹配（如果是 load 得到的）
    private String mJobName = "JOB-FROM-"+this;
    // 本地的镜像存储地址，会在任何修改后保存到此镜像
    private String mMirrorPath = null;
    
    /// hooks, 修改这个来实现重写，我也不知道这个方法是不是合理
    // 发生内部参数改变都需要调用一下这个函数
    Runnable doMemberChange = () -> {if (mMirrorPath != null) mNeedSaveMirror = true;};
    // 内部使用的保存到镜像的方法
    void saveToMirror_() {
        if (!mNeedSaveMirror) return;
        // 保存之前先检测 mirror 是否还是自己的，如果不是则需要 kill（但是这个改动已经发生，这是不可避免的）
        boolean tMirrorValid = false;
        try (FileReader tFile = new FileReader(mMirrorPath)) {
            JSONObject tJson = (JSONObject) new JSONParser().parse(tFile);
            tMirrorValid = ((JSONObject) tJson.get("SLURM")).get("MirrorOwner").equals(this.toString());
        } catch (IOException | ParseException ignored) {}
        if (!tMirrorValid) {
            System.out.printf("WARNING: Exist redundant instance of mirror(%s),\n", this);
            System.out.println("  which has been killed, but may still have some redundant submit,");
            System.out.println("  you need to kill the old instance before load the mirror.");
            System.out.flush();
            mNeedSaveMirror = false;
            kill();
            return;
        }
        // 开始保存镜像
        boolean oPause = mPause; // 记录原本的暂停状态
        try {save(mMirrorPath);} catch (IOException ignored) {}
        finally {mPause = oPause;} // 还原暂停状态（因为 save 会改变暂停状态，目前无论如何 save 都会完全暂停）
        // 最后重置镜像保存需求
        mNeedSaveMirror = false;
    }
    
    /// 保存到文件以及从文件加载
    public void save(String aFilePath) throws IOException {
        JSONObject rJson = new JSONObject();
        save(rJson);
        FileWriter tFile = new FileWriter(aFilePath);
        JSONObject.writeJSONString(rJson, tFile);
        tFile.close();
    }
    public static ServerSLURM load(String aFilePath) throws Exception {
        FileReader tFile = new FileReader(aFilePath);
        JSONObject tJson = (JSONObject) new JSONParser().parse(tFile);
        tFile.close();
        return load(tJson);
    }
    // 偏向于内部使用的保存到 json 和从 json 读取
    @SuppressWarnings("unchecked")
    public void save(JSONObject rJson) {
        // 先暂停然后保存，防止保存过程中出现了修改
        pause();
        // 先保存 ssh
        JSONObject rJsonSSH = new JSONObject();
        rJson.put("SSH", rJsonSSH);
        mSSH.save(rJsonSSH);
        // 再保存 slurm
        JSONObject rJsonSLURM = new JSONObject();
        rJson.put("SLURM", rJsonSLURM);
        rJsonSLURM.put("MaxJobNumber", mMaxJobNumber);
        rJsonSLURM.put("SleepTime", mSleepTime);
        rJsonSLURM.put("JobName", mJobName);
        rJsonSLURM.put("Tolerant", mTolerantCounter.mTolerant);
        
        if (mMaxThisJobNumber < mMaxJobNumber)
            rJsonSLURM.put("MaxThisJobNumber", mMaxThisJobNumber);
        if (!mSqueueName.equals(mSSH.session().getUserName()))
            rJsonSLURM.put("SqueueName", mSqueueName);
        if (mMirrorPath != null) {
            rJsonSLURM.put("MirrorPath", mMirrorPath);
            rJsonSLURM.put("MirrorOwner", this.toString()); // 用来在 mirror 切换对象时防止重复提交
        }
        
        synchronized (mJobIDList) {
        if (!mJobIDList.isEmpty()) {
            JSONArray rJsonJobIDList = new JSONArray();
            rJsonSLURM.put("JobIDList", rJsonJobIDList);
            // 按照 id，task 的顺序排列
            for (Map.Entry<Integer, Pair<Task, Integer>> tEntry : mJobIDList.entrySet()) {
                rJsonJobIDList.add(tEntry.getKey());
                Task tTask = tEntry.getValue().first;
                rJsonJobIDList.add(tTask==null?Task.Type.NULL.name():tTask.toString());
            }
        }}
        
        synchronized (mCommandList) {
        if (!mCommandList.isEmpty()) {
            JSONArray rJsonCommandList = new JSONArray();
            rJsonSLURM.put("CommandList", rJsonCommandList);
            // 按照 command, beforeTask, afterTask 的顺序排列
            for (Pair<Pair<Task, Task>, String> tPair : mCommandList) {
                rJsonCommandList.add(tPair.second);
                Task tBeforeTask = tPair.first.first;
                Task tAfterTask = tPair.first.second;
                rJsonCommandList.add(tBeforeTask==null?Task.Type.NULL.name():tBeforeTask.toString());
                rJsonCommandList.add(tAfterTask==null?Task.Type.NULL.name():tAfterTask.toString());
            }
        }}
        
        // save 操作不自动解除暂停以防止重复提交
    }
    public static ServerSLURM load(JSONObject aJson) throws Exception {
        // 先加载 ssh
        ServerSSH aSSH = ServerSSH.load((JSONObject) aJson.get("SSH"));
        // 再加载 slurm
        JSONObject tJsonSLURM = (JSONObject) aJson.get("SLURM");
        int aMaxJobNumber = ((Number) tJsonSLURM.get("MaxJobNumber")).intValue();
        long aSleepTime = ((Number) tJsonSLURM.get("SleepTime")).longValue();
        String aJobName = (String) tJsonSLURM.get("JobName");
        int aTolerant = ((Number) tJsonSLURM.get("Tolerant")).intValue();
        String aSqueueName = tJsonSLURM.containsKey("SqueueName") ? (String) tJsonSLURM.get("SqueueName") : aSSH.session().getUserName();
        
        ServerSLURM rServerSLURM;
        if (tJsonSLURM.containsKey("MaxThisJobNumber")) rServerSLURM = new ServerSLURM(aSSH, aMaxJobNumber, ((Number) tJsonSLURM.get("MaxThisJobNumber")).intValue(), aSqueueName);
        else rServerSLURM = new ServerSLURM(aSSH, aMaxJobNumber, aSqueueName);
        // 获取后先暂停防止加载过程中发生了提交
        rServerSLURM.pause();
        
        rServerSLURM.setSleepTime(aSleepTime).setTolerant(aTolerant);
        rServerSLURM.mJobName = aJobName;
        
        // 获取任务队列
        if (tJsonSLURM.containsKey("JobIDList")) synchronized (rServerSLURM.mJobIDList) {
            JSONArray tJsonJobIDList = (JSONArray) tJsonSLURM.get("JobIDList");
            for (int i = 1; i < tJsonJobIDList.size(); i+=2)
                rServerSLURM.mJobIDList.put(((Number) tJsonJobIDList.get(i-1)).intValue(), new Pair<>(Task.fromString(rServerSLURM, (String) tJsonJobIDList.get(i)), DEFAULT_TOLERANT));
        }
        // 获取排队队列
        if (tJsonSLURM.containsKey("CommandList")) synchronized (rServerSLURM.mCommandList) {
            JSONArray tJsonCommandList = (JSONArray) tJsonSLURM.get("CommandList");
            for (int i = 2; i < tJsonCommandList.size(); i+=3)
                rServerSLURM.mCommandList.add(new Pair<>(new Pair<>(Task.fromString(rServerSLURM, (String) tJsonCommandList.get(i-1)), Task.fromString(rServerSLURM, (String) tJsonCommandList.get(i))), (String) tJsonCommandList.get(i-2)));
        }
        // 最后加载 MirrorPath，会自动进行存储一次
        if (tJsonSLURM.containsKey("MirrorPath")) rServerSLURM.setMirror((String) tJsonSLURM.get("MirrorPath"));
        
        // 加载完成解除暂停
        rServerSLURM.unpause();
        return rServerSLURM;
    }
    
    
    /// 构造函数以及获取方式（用来区分私钥登录以及密码登录）
    private ServerSLURM(ServerSSH aSSH, int aMaxJobNumber, String aSqueueName) {this(aSSH, aMaxJobNumber, aMaxJobNumber, aSqueueName);}
    private ServerSLURM(ServerSSH aSSH, int aMaxJobNumber, int aMaxThisJobNumber, String aSqueueName) {
        mSSH = aSSH;
        mSSH.doMemberChange = doMemberChange; // 重写 mSSH 的 doMemberChange
        mMaxJobNumber = aMaxJobNumber;
        mMaxThisJobNumber = aMaxThisJobNumber;
        mSqueueName = aSqueueName;
        // 初始化线程池
        mPool = Executors.newSingleThreadExecutor();
        // 提交长期任务，不断从 mCmdList 获取指令并执行
        mPool.execute(this::keepSubmitFromList_);
    }
    // 不提供密码则认为是私钥登录，提供密码则认为是密码登录
    public static ServerSLURM get   (int aMaxJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname                             ) {ServerSSH tSSH = ServerSSH.get   (aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname                  ); return new ServerSLURM(tSSH, aMaxJobNumber, tSSH.session().getUserName());}
    public static ServerSLURM get   (int aMaxJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname, int aPort                  ) {ServerSSH tSSH = ServerSSH.get   (aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname, aPort           ); return new ServerSLURM(tSSH, aMaxJobNumber, tSSH.session().getUserName());}
    public static ServerSLURM get   (int aMaxJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname,            String aPassword) {ServerSSH tSSH = ServerSSH.get   (aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname,        aPassword); return new ServerSLURM(tSSH, aMaxJobNumber, tSSH.session().getUserName());}
    public static ServerSLURM get   (int aMaxJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname, int aPort, String aPassword) {ServerSSH tSSH = ServerSSH.get   (aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname, aPort, aPassword); return new ServerSLURM(tSSH, aMaxJobNumber, tSSH.session().getUserName());}
    public static ServerSLURM getKey(int aMaxJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname,            String aKeyPath ) {ServerSSH tSSH = ServerSSH.getKey(aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname,        aKeyPath ); return new ServerSLURM(tSSH, aMaxJobNumber, tSSH.session().getUserName());}
    public static ServerSLURM getKey(int aMaxJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname, int aPort, String aKeyPath ) {ServerSSH tSSH = ServerSSH.getKey(aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname, aPort, aKeyPath ); return new ServerSLURM(tSSH, aMaxJobNumber, tSSH.session().getUserName());}
    
    public static ServerSLURM get   (String aSqueueName, int aMaxJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname                             ) {ServerSSH tSSH = ServerSSH.get   (aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname                  ); return new ServerSLURM(tSSH, aMaxJobNumber, aSqueueName);}
    public static ServerSLURM get   (String aSqueueName, int aMaxJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname, int aPort                  ) {ServerSSH tSSH = ServerSSH.get   (aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname, aPort           ); return new ServerSLURM(tSSH, aMaxJobNumber, aSqueueName);}
    public static ServerSLURM get   (String aSqueueName, int aMaxJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname,            String aPassword) {ServerSSH tSSH = ServerSSH.get   (aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname,        aPassword); return new ServerSLURM(tSSH, aMaxJobNumber, aSqueueName);}
    public static ServerSLURM get   (String aSqueueName, int aMaxJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname, int aPort, String aPassword) {ServerSSH tSSH = ServerSSH.get   (aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname, aPort, aPassword); return new ServerSLURM(tSSH, aMaxJobNumber, aSqueueName);}
    public static ServerSLURM getKey(String aSqueueName, int aMaxJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname,            String aKeyPath ) {ServerSSH tSSH = ServerSSH.getKey(aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname,        aKeyPath ); return new ServerSLURM(tSSH, aMaxJobNumber, aSqueueName);}
    public static ServerSLURM getKey(String aSqueueName, int aMaxJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname, int aPort, String aKeyPath ) {ServerSSH tSSH = ServerSSH.getKey(aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname, aPort, aKeyPath ); return new ServerSLURM(tSSH, aMaxJobNumber, aSqueueName);}
    
    public static ServerSLURM get   (int aMaxJobNumber, int aMaxThisJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname                             ) {ServerSSH tSSH = ServerSSH.get   (aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname                  ); return new ServerSLURM(tSSH, aMaxJobNumber, aMaxThisJobNumber, tSSH.session().getUserName());}
    public static ServerSLURM get   (int aMaxJobNumber, int aMaxThisJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname, int aPort                  ) {ServerSSH tSSH = ServerSSH.get   (aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname, aPort           ); return new ServerSLURM(tSSH, aMaxJobNumber, aMaxThisJobNumber, tSSH.session().getUserName());}
    public static ServerSLURM get   (int aMaxJobNumber, int aMaxThisJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname,            String aPassword) {ServerSSH tSSH = ServerSSH.get   (aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname,        aPassword); return new ServerSLURM(tSSH, aMaxJobNumber, aMaxThisJobNumber, tSSH.session().getUserName());}
    public static ServerSLURM get   (int aMaxJobNumber, int aMaxThisJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname, int aPort, String aPassword) {ServerSSH tSSH = ServerSSH.get   (aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname, aPort, aPassword); return new ServerSLURM(tSSH, aMaxJobNumber, aMaxThisJobNumber, tSSH.session().getUserName());}
    public static ServerSLURM getKey(int aMaxJobNumber, int aMaxThisJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname,            String aKeyPath ) {ServerSSH tSSH = ServerSSH.getKey(aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname,        aKeyPath ); return new ServerSLURM(tSSH, aMaxJobNumber, aMaxThisJobNumber, tSSH.session().getUserName());}
    public static ServerSLURM getKey(int aMaxJobNumber, int aMaxThisJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname, int aPort, String aKeyPath ) {ServerSSH tSSH = ServerSSH.getKey(aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname, aPort, aKeyPath ); return new ServerSLURM(tSSH, aMaxJobNumber, aMaxThisJobNumber, tSSH.session().getUserName());}
    
    public static ServerSLURM get   (String aSqueueName, int aMaxJobNumber, int aMaxThisJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname                             ) {ServerSSH tSSH = ServerSSH.get   (aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname                  ); return new ServerSLURM(tSSH, aMaxJobNumber, aMaxThisJobNumber, aSqueueName);}
    public static ServerSLURM get   (String aSqueueName, int aMaxJobNumber, int aMaxThisJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname, int aPort                  ) {ServerSSH tSSH = ServerSSH.get   (aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname, aPort           ); return new ServerSLURM(tSSH, aMaxJobNumber, aMaxThisJobNumber, aSqueueName);}
    public static ServerSLURM get   (String aSqueueName, int aMaxJobNumber, int aMaxThisJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname,            String aPassword) {ServerSSH tSSH = ServerSSH.get   (aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname,        aPassword); return new ServerSLURM(tSSH, aMaxJobNumber, aMaxThisJobNumber, aSqueueName);}
    public static ServerSLURM get   (String aSqueueName, int aMaxJobNumber, int aMaxThisJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname, int aPort, String aPassword) {ServerSSH tSSH = ServerSSH.get   (aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname, aPort, aPassword); return new ServerSLURM(tSSH, aMaxJobNumber, aMaxThisJobNumber, aSqueueName);}
    public static ServerSLURM getKey(String aSqueueName, int aMaxJobNumber, int aMaxThisJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname,            String aKeyPath ) {ServerSSH tSSH = ServerSSH.getKey(aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname,        aKeyPath ); return new ServerSLURM(tSSH, aMaxJobNumber, aMaxThisJobNumber, aSqueueName);}
    public static ServerSLURM getKey(String aSqueueName, int aMaxJobNumber, int aMaxThisJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname, int aPort, String aKeyPath ) {ServerSSH tSSH = ServerSSH.getKey(aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname, aPort, aKeyPath ); return new ServerSLURM(tSSH, aMaxJobNumber, aMaxThisJobNumber, aSqueueName);}
    /// 提供通用的接口，这里直接返回内部的 SSH 省去重复的转发部分
    public ServerSSH ssh() {
        if (mDead) throw new RuntimeException("Can NOT get SSH from a Dead SLURM.");
        return mSSH;
    }
    public void shutdown() {
        mDead = true;
        mPool.shutdown();
    }
    public void shutdownNow() throws JSchException, IOException {
        cancelThis();
        mDead = true;
        mPool.shutdown();
    }
    public void pause() {
        mPause = true;
        // 除了设置暂停，还会挂起直到这批任务提交完成（即等待直到真正暂停）
        while (mSubmitting) try {Thread.sleep(200);} catch (InterruptedException e) {e.printStackTrace(); break;}
    }
    public void unpause() {mPause = false;}
    // 直接杀死这个对象，类似于系统层面的杀死进程，会直接关闭提交任务并且放弃监管远程服务器的任务而不是取消这些任务，从而使得 mirror 的内容冻结
    public void kill() {kill(true);}
    public void kill(boolean aWarning) {
        // 会先暂停保证正在进行的任务已经完成提交，保证镜像文件不会被这个对象再次修改
        pause();
        // 直接设置 mKilled 即可
        if (aWarning && mMirrorPath == null) System.out.println("WARNING: you killed a slurm without mirror, jobs submit from this may out of control!");
        mKilled = true;
        mDead = true;
        mPool.shutdown();
    }
    // 一些参数设置
    public ServerSLURM setSleepTime(long aSleepTime) {
        if (mDead) throw new RuntimeException("Can NOT setSleepTime from a Dead SLURM.");
        mSleepTime = aSleepTime;
        doMemberChange.run();
        return this;
    }
    public ServerSLURM setTolerant(int aTolerant) {
        if (mDead) throw new RuntimeException("Can NOT setTolerant from a Dead SLURM.");
        mTolerantCounter.mTolerant = aTolerant;
        mTolerantCounter.mUsedTolerant = Math.min(mTolerantCounter.mUsedTolerant, mTolerantCounter.mTolerant);
        doMemberChange.run(); return this;
    }
    public ServerSLURM setMirror(String aPath) {
        if (mDead) throw new RuntimeException("Can NOT setMirror from a Dead SLURM.");
        if (aPath.isEmpty()) {mMirrorPath = null; return this;}
        mMirrorPath = aPath;
        boolean oPause = mPause; // 记录原本的暂停状态
        try {save(aPath);}
        catch (IOException e) {
            System.out.println("WARNING: set MirrorPath to "+aPath+" Fail, MirrorPath set to null.");
            mMirrorPath = null;
        }
        finally {mPause = oPause;} // 还原暂停状态（因为 save 会改变暂停状态，目前无论如何 save 都会完全暂停）
        return this;
    }
    
    /// 内部实用类
    // 内部的从队列中提交任务
    void keepSubmitFromList_() {
        while (true) {
            mSubmitting = false;
            // 在这里执行保存到镜像的操作
            saveToMirror_();
            // 由于检测任务是否完成也需要发送指令，简单起见这里直接限制提交频率为 0.5s 一次（默认）
            try {Thread.sleep(mSleepTime);} catch (InterruptedException e) {e.printStackTrace(); break;}
            // 如果被杀死则直接结束（优先级最高）
            if (mKilled) break;
            // 如果已经暂停则直接跳过
            if (mPause) continue;
            // 开始提交任务相关事项，这里在任务提交完成之前都保持 mCommandList 和 mJobIDList 的占用，保证 getTaskNumber 一般会获得正确的值
            synchronized (mCommandList) {synchronized (mJobIDList) {
                mSubmitting = true;
                // 如果没有指令需要提交，并且没有正在执行的任务则需要考虑关闭线程
                if (mCommandList.isEmpty() && mJobIDList.isEmpty()) {if (mDead) break; else continue;}
                // 这里统一检查一次联机状态，如果重新连接失败直接跳过重试
                if (!mSSH.isConnecting()) try {mSSH.connect();} catch (JSchException e) {continue;}
                // 首先获取正在执行的任务队列
                Set<Integer> tJobIDs;
                try {tJobIDs = jobIDs_();} catch (JSchException | IOException e) {continue;} // 获取失败则直接跳过重试
                // 首先更新正在执行的任务列表
                if (!mJobIDList.isEmpty()) {
                    // 将不存在 JobIDs 中的计数减一，因为可能因为网络问题导致 jobIDs_ 获取的结果不一定正确
                    for (Map.Entry<Integer, Pair<Task, Integer>> tEntry : mJobIDList.entrySet()) {
                        if (!tJobIDs.contains(tEntry.getKey())) --(tEntry.getValue().second);
                        else tEntry.getValue().second = DEFAULT_TOLERANT;
                    }
                    // 将计数小于 1 的移除
                    final Iterator<Pair<Task, Integer>> tIt = mJobIDList.values().iterator();
                    final boolean[] tAlive = {true};
                    while (tAlive[0] && tIt.hasNext()) {
                        Pair<Task, Integer> tPair = tIt.next();
                        if (tPair.second < 0) {
                            // 移除前先执行完成后的 task
                            Task tAfterSystem = tPair.first;
                            if (tAfterSystem != null) {
                                boolean tSuc;
                                try {tSuc = tAfterSystem.run();} catch (Exception e) {tSuc = false;}
                                mTolerantCounter.call(tSuc, "running after task: "+tAfterSystem, () -> {tIt.remove(); doMemberChange.run();}, () -> tAlive[0] = false, () -> {tIt.remove(); doMemberChange.run();});
                            } else {
                                tIt.remove(); doMemberChange.run();
                            }
                        }
                    }
                    // 如果期间发生了不成功的现象，则 tAlive 为 false，不再进行后续操作并重试
                    if (!tAlive[0]) continue;
                }
                // 准备提交任务，如果没有任务则跳过
                if (mCommandList.isEmpty()) continue;
                // 如果正在执行的任务列表超过限制，则不会提交
                if (tJobIDs.size() >= mMaxJobNumber) continue;
                // 如果此对象正在执行的任务超过限制，则不会提交
                if (mJobIDList.size() >= mMaxThisJobNumber) continue;
                // 获取和提交任务
                Pair<Pair<Task, Task>, String> tPair = mCommandList.peekFirst();
                if (tPair == null) continue;
                final Pair<Task, Task> tTasks = tPair.first;
                final String tCommand = tPair.second;
                if (tTasks.first != null) {
                    boolean tSuc;
                    try {tSuc = tTasks.first.run();} catch (Exception e) {tSuc = false;}
                    mTolerantCounter.call(tSuc, "running before task: "+tTasks.first, () -> {mCommandList.removeFirst(); doMemberChange.run();}, () -> {}, () -> {tTasks.first = null; doMemberChange.run();});
                    if (!tSuc) continue; // 只要不成功都需要跳过后续并重试
                }
                // 获取执行命令的通道
                ChannelExec tChannelExec = null;
                try {tChannelExec = mSSH.systemChannel(tCommand);} catch (JSchException ignored) {}
                mTolerantCounter.call(tChannelExec != null, "get ChannelExec: "+tCommand, () -> {mCommandList.removeFirst(); doMemberChange.run();});
                if (tChannelExec == null) continue; // 只要不成功都需要跳过后续并重试
                // 提交命令并且获取任务号
                int tJobID = getJobIDFromChannel_(tChannelExec);
                mTolerantCounter.call(tJobID > 0, "get JobID("+tJobID+"): "+tCommand, () -> {mCommandList.removeFirst(); doMemberChange.run();});
                if (tJobID <= 0) continue; // 只要不成功都需要跳过后续并重试
                // 成功获取，移出 mCommandList 并添加到 mJobIDList
                mCommandList.removeFirst();
                mJobIDList.put(tJobID, new Pair<>(tTasks.second, DEFAULT_TOLERANT));
                doMemberChange.run();
            }}
        }
        mSubmitting = false;
        // 最后再执行一次保存到镜像的操作
        saveToMirror_();
        // 最后关闭 SSH 通道
        mSSH.shutdown();
    }
    
    // 从 aChannelExec 中获取任务号，返回小于零的值表示获取失败。会在内部开启通道来获得输出，因此获取完成后会直接关闭通道
    static int getJobIDFromChannel_(ChannelExec aChannelExec) {
        InputStream tIn;
        try {tIn = aChannelExec.getInputStream();} catch (IOException e) {return -1;}
        // 开启通道获取输出
        try {aChannelExec.connect();} catch (JSchException e) {return -2;}
        BufferedReader tReader = new BufferedReader(new InputStreamReader(tIn));
        String tLine;
        try {tLine = tReader.readLine();} catch (IOException e) {return -3;} // 只需要读取一行
        try {tReader.close();} catch (IOException e) {return -4;}
        // 会在内部关闭通道
        aChannelExec.disconnect();
        // 返回任务号
        if (tLine != null && tLine.startsWith("Submitted batch job ")) return Integer.parseInt(tLine.substring(20));
        return -5;
    }
    // 获取创建输出文件所需要创建文件夹的 task，这里只用于减少重复代码
    Task task_validPath_(final String aPath) {
        int tEndIdx = aPath.lastIndexOf("/");
        if (tEndIdx > 0) { // 否则不用创建，认为 mRemoteWorkingDir 已经存在
            final String tDir = aPath.substring(0, tEndIdx+1);
            return mSSH.task_mkdir(tDir);
        }
        return null;
    }
    
    /**
     * 通用的提交任务接口，底层使用 sbatch 来提交任务
     * 只需要指定需要运行的目标分区以及总的需要的节点数目
     * 一般来说需要在指令中自己使用 srun 来开始并行任务
     * 可以指定输出目录
     * 可以指定指令开始之前的 task 以及指令执行完成后的 task
     */
    public void submitSystem(String aCommand                                                        ) {submitSystem(aCommand, null);}
    public void submitSystem(String aCommand,                    int aNodeNumber                    ) {submitSystem(aCommand, null, aNodeNumber);}
    public void submitSystem(String aCommand,                    int aNodeNumber, String aOutputPath) {submitSystem(aCommand, null, aNodeNumber, aOutputPath);}
    public void submitSystem(String aCommand, String aPartition                                     ) {submitSystem(aCommand, aPartition, 1);}
    public void submitSystem(String aCommand, String aPartition, int aNodeNumber                    ) {submitSystem(aCommand, aPartition, aNodeNumber, ".temp/slurm/out-%j");}
    public void submitSystem(String aCommand, String aPartition, int aNodeNumber, String aOutputPath) {submitSystem(null, aCommand, aPartition, aNodeNumber, aOutputPath);}
    
    public void submitSystem(Task aBeforeSystem, String aCommand                                                        ) {submitSystem(aBeforeSystem, aCommand, null);}
    public void submitSystem(Task aBeforeSystem, String aCommand,                    int aNodeNumber                    ) {submitSystem(aBeforeSystem, aCommand, null, aNodeNumber);}
    public void submitSystem(Task aBeforeSystem, String aCommand,                    int aNodeNumber, String aOutputPath) {submitSystem(aBeforeSystem, aCommand, null, aNodeNumber, aOutputPath);}
    public void submitSystem(Task aBeforeSystem, String aCommand, String aPartition                                     ) {submitSystem(aBeforeSystem, aCommand, aPartition, 1);}
    public void submitSystem(Task aBeforeSystem, String aCommand, String aPartition, int aNodeNumber                    ) {submitSystem(aBeforeSystem, aCommand, aPartition, aNodeNumber, ".temp/slurm/out-%j");}
    public void submitSystem(Task aBeforeSystem, String aCommand, String aPartition, int aNodeNumber, String aOutputPath) {submitSystem(aBeforeSystem, null, aCommand, aPartition, aNodeNumber, aOutputPath);}
    
    public void submitSystem(Task aBeforeSystem, Task aAfterSystem, String aCommand                                                        ) {submitSystem(aBeforeSystem, aAfterSystem, aCommand, null);}
    public void submitSystem(Task aBeforeSystem, Task aAfterSystem, String aCommand,                    int aNodeNumber                    ) {submitSystem(aBeforeSystem, aAfterSystem, aCommand, null, aNodeNumber);}
    public void submitSystem(Task aBeforeSystem, Task aAfterSystem, String aCommand,                    int aNodeNumber, String aOutputPath) {submitSystem(aBeforeSystem, aAfterSystem, aCommand, null, aNodeNumber, aOutputPath);}
    public void submitSystem(Task aBeforeSystem, Task aAfterSystem, String aCommand, String aPartition                                     ) {submitSystem(aBeforeSystem, aAfterSystem, aCommand, aPartition, 1);}
    public void submitSystem(Task aBeforeSystem, Task aAfterSystem, String aCommand, String aPartition, int aNodeNumber                    ) {submitSystem(aBeforeSystem, aAfterSystem, aCommand, aPartition, aNodeNumber, ".temp/slurm/out-%j");}
    public void submitSystem(Task aBeforeSystem, Task aAfterSystem, String aCommand, String aPartition, int aNodeNumber, String aOutputPath) {
        if (mDead) throw new RuntimeException("Can NOT submitSbatch from a Dead SLURM.");
        aNodeNumber = Math.max(1, aNodeNumber);
        // 需要创建输出目录的文件夹
        aBeforeSystem = mergeTask(aBeforeSystem, task_validPath_(aOutputPath));
        // 组装指令
        aCommand = String.format("echo -e '#!/bin/bash\\n%s' | sbatch --nodes %d --output %s --job-name %s", aCommand, aNodeNumber, aOutputPath, mJobName);
        if (aPartition != null && !aPartition.isEmpty()) aCommand += String.format(" --partition %s", aPartition);
        // 添加指令到队列
        synchronized (mCommandList) {mCommandList.addLast(new Pair<>(new Pair<>(aBeforeSystem, aAfterSystem), aCommand)); doMemberChange.run();}
    }
    
    /**
     * 直接使用 sbatch 执行脚本的接口
     * 可以指定需要运行的目标分区以及总的需要的节点数目
     * 可以不指定需要的节点数目而在脚本中指定
     * 输入本地的脚本路径，首先会将其上传到服务器对应位置
     */
    public void submitBash(String aBashPath                                                        ) {submitBash(aBashPath, null);}
    public void submitBash(String aBashPath,                    int aNodeNumber                    ) {submitBash(aBashPath, null, aNodeNumber);}
    public void submitBash(String aBashPath,                    int aNodeNumber, String aOutputPath) {submitBash(aBashPath, null, aNodeNumber, aOutputPath);}
    public void submitBash(String aBashPath, String aPartition                                     ) {submitBash(aBashPath, aPartition, -1);}
    public void submitBash(String aBashPath, String aPartition, int aNodeNumber                    ) {submitBash(aBashPath, aPartition, aNodeNumber, ".temp/slurm/out-%j");}
    public void submitBash(String aBashPath, String aPartition, int aNodeNumber, String aOutputPath) {submitBash(null, aBashPath, aPartition, aNodeNumber, aOutputPath);}
    
    public void submitBash(Task aBeforeSystem, String aBashPath                                                        ) {submitBash(aBeforeSystem, aBashPath, null);}
    public void submitBash(Task aBeforeSystem, String aBashPath,                    int aNodeNumber                    ) {submitBash(aBeforeSystem, aBashPath, null, aNodeNumber);}
    public void submitBash(Task aBeforeSystem, String aBashPath,                    int aNodeNumber, String aOutputPath) {submitBash(aBeforeSystem, aBashPath, null, aNodeNumber, aOutputPath);}
    public void submitBash(Task aBeforeSystem, String aBashPath, String aPartition                                     ) {submitBash(aBeforeSystem, aBashPath, aPartition, -1);}
    public void submitBash(Task aBeforeSystem, String aBashPath, String aPartition, int aNodeNumber                    ) {submitBash(aBeforeSystem, aBashPath, aPartition, aNodeNumber, ".temp/slurm/out-%j");}
    public void submitBash(Task aBeforeSystem, String aBashPath, String aPartition, int aNodeNumber, String aOutputPath) {submitBash(aBeforeSystem, null, aBashPath, aPartition, aNodeNumber, aOutputPath);}
    
    public void submitBash(Task aBeforeSystem, Task aAfterSystem, String aBashPath                                                        ) {submitBash(aBeforeSystem, aAfterSystem, aBashPath, null);}
    public void submitBash(Task aBeforeSystem, Task aAfterSystem, String aBashPath,                    int aNodeNumber                    ) {submitBash(aBeforeSystem, aAfterSystem, aBashPath, null, aNodeNumber);}
    public void submitBash(Task aBeforeSystem, Task aAfterSystem, String aBashPath,                    int aNodeNumber, String aOutputPath) {submitBash(aBeforeSystem, aAfterSystem, aBashPath, null, aNodeNumber, aOutputPath);}
    public void submitBash(Task aBeforeSystem, Task aAfterSystem, String aBashPath, String aPartition                                     ) {submitBash(aBeforeSystem, aAfterSystem, aBashPath, aPartition, -1);}
    public void submitBash(Task aBeforeSystem, Task aAfterSystem, String aBashPath, String aPartition, int aNodeNumber                    ) {submitBash(aBeforeSystem, aAfterSystem, aBashPath, aPartition, aNodeNumber, ".temp/slurm/out-%j");}
    public void submitBash(Task aBeforeSystem, Task aAfterSystem, String aBashPath, String aPartition, int aNodeNumber, String aOutputPath) {
        if (mDead) throw new RuntimeException("Can NOT submitBash from a Dead SLURM.");
        // 需要创建输出目录的文件夹
        aBeforeSystem = mergeTask(aBeforeSystem, task_validPath_(aOutputPath));
        // 并且需要上传脚本
        aBeforeSystem = mergeTask(aBeforeSystem, mSSH.task_putFile(aBashPath));
        // 组装指令
        String tCommand = String.format("sbatch --output %s --job-name %s", aOutputPath, mJobName);
        if (aPartition != null && !aPartition.isEmpty()) tCommand += String.format(" --partition %s", aPartition);
        if (aNodeNumber > 0) tCommand += String.format(" --nodes %d", aNodeNumber);
        tCommand += String.format(" %s", aBashPath);
        // 添加指令到队列
        synchronized (mCommandList) {mCommandList.addLast(new Pair<>(new Pair<>(aBeforeSystem, aAfterSystem), tCommand)); doMemberChange.run();}
    }
    
    /**
     * 提供一个直接使用 srun 执行指令的接口，实际会使用 sbatch 将任务挂到后台
     * 输入具体的指令，分区，任务数目（并行数目），每节点的最多任务数（用于计算节点数目）
     * 可以指定输出目录
     */
    public void submitSrun(String aCommand                                                                                   ) {submitSrun(aCommand, null);}
    public void submitSrun(String aCommand,                    int aTaskNumber                                               ) {submitSrun(aCommand, null, aTaskNumber);}
    public void submitSrun(String aCommand,                    int aTaskNumber, int aMaxTaskNumberPerNode                    ) {submitSrun(aCommand, null, aTaskNumber, aMaxTaskNumberPerNode);}
    public void submitSrun(String aCommand,                    int aTaskNumber, int aMaxTaskNumberPerNode, String aOutputPath) {submitSrun(aCommand, null, aTaskNumber, aMaxTaskNumberPerNode, aOutputPath);}
    public void submitSrun(String aCommand, String aPartition                                                                ) {submitSrun(aCommand, aPartition, 1);}
    public void submitSrun(String aCommand, String aPartition, int aTaskNumber                                               ) {submitSrun(aCommand, aPartition, aTaskNumber, 20);}
    public void submitSrun(String aCommand, String aPartition, int aTaskNumber, int aMaxTaskNumberPerNode                    ) {submitSrun(aCommand, aPartition, aTaskNumber, aMaxTaskNumberPerNode, ".temp/slurm/out-%j");}
    public void submitSrun(String aCommand, String aPartition, int aTaskNumber, int aMaxTaskNumberPerNode, String aOutputPath) {submitSrun(null, aCommand, aPartition, aTaskNumber, aMaxTaskNumberPerNode, aOutputPath);}
    
    public void submitSrun(Task aBeforeSystem, String aCommand                                                                                   ) {submitSrun(aBeforeSystem, aCommand, null);}
    public void submitSrun(Task aBeforeSystem, String aCommand,                    int aTaskNumber                                               ) {submitSrun(aBeforeSystem, aCommand, null, aTaskNumber);}
    public void submitSrun(Task aBeforeSystem, String aCommand,                    int aTaskNumber, int aMaxTaskNumberPerNode                    ) {submitSrun(aBeforeSystem, aCommand, null, aTaskNumber, aMaxTaskNumberPerNode);}
    public void submitSrun(Task aBeforeSystem, String aCommand,                    int aTaskNumber, int aMaxTaskNumberPerNode, String aOutputPath) {submitSrun(aBeforeSystem, aCommand, null, aTaskNumber, aMaxTaskNumberPerNode, aOutputPath);}
    public void submitSrun(Task aBeforeSystem, String aCommand, String aPartition                                                                ) {submitSrun(aBeforeSystem, aCommand, aPartition, 1);}
    public void submitSrun(Task aBeforeSystem, String aCommand, String aPartition, int aTaskNumber                                               ) {submitSrun(aBeforeSystem, aCommand, aPartition, aTaskNumber, 20);}
    public void submitSrun(Task aBeforeSystem, String aCommand, String aPartition, int aTaskNumber, int aMaxTaskNumberPerNode                    ) {submitSrun(aBeforeSystem, aCommand, aPartition, aTaskNumber, aMaxTaskNumberPerNode, ".temp/slurm/out-%j");}
    public void submitSrun(Task aBeforeSystem, String aCommand, String aPartition, int aTaskNumber, int aMaxTaskNumberPerNode, String aOutputPath) {submitSrun(aBeforeSystem, null, aCommand, aPartition, aTaskNumber, aMaxTaskNumberPerNode, aOutputPath);}
    
    public void submitSrun(Task aBeforeSystem, Task aAfterSystem, String aCommand                                                                                   ) {submitSrun(aBeforeSystem, aAfterSystem, aCommand, null);}
    public void submitSrun(Task aBeforeSystem, Task aAfterSystem, String aCommand,                    int aTaskNumber                                               ) {submitSrun(aBeforeSystem, aAfterSystem, aCommand, null, aTaskNumber);}
    public void submitSrun(Task aBeforeSystem, Task aAfterSystem, String aCommand,                    int aTaskNumber, int aMaxTaskNumberPerNode                    ) {submitSrun(aBeforeSystem, aAfterSystem, aCommand, null, aTaskNumber, aMaxTaskNumberPerNode);}
    public void submitSrun(Task aBeforeSystem, Task aAfterSystem, String aCommand,                    int aTaskNumber, int aMaxTaskNumberPerNode, String aOutputPath) {submitSrun(aBeforeSystem, aAfterSystem, aCommand, null, aTaskNumber, aMaxTaskNumberPerNode, aOutputPath);}
    public void submitSrun(Task aBeforeSystem, Task aAfterSystem, String aCommand, String aPartition                                                                ) {submitSrun(aBeforeSystem, aAfterSystem, aCommand, aPartition, 1);}
    public void submitSrun(Task aBeforeSystem, Task aAfterSystem, String aCommand, String aPartition, int aTaskNumber                                               ) {submitSrun(aBeforeSystem, aAfterSystem, aCommand, aPartition, aTaskNumber, 20);}
    public void submitSrun(Task aBeforeSystem, Task aAfterSystem, String aCommand, String aPartition, int aTaskNumber, int aMaxTaskNumberPerNode                    ) {submitSrun(aBeforeSystem, aAfterSystem, aCommand, aPartition, aTaskNumber, aMaxTaskNumberPerNode, ".temp/slurm/out-%j");}
    public void submitSrun(Task aBeforeSystem, Task aAfterSystem, String aCommand, String aPartition, int aTaskNumber, int aMaxTaskNumberPerNode, String aOutputPath) {
        if (mDead) throw new RuntimeException("Can NOT submitSrun from a Dead SLURM.");
        aTaskNumber = Math.max(1, aTaskNumber);
        aMaxTaskNumberPerNode = Math.max(1, aMaxTaskNumberPerNode);
        submitSystem(aBeforeSystem, aAfterSystem, String.format("srun --ntasks %d --ntasks-per-node %d --wait 1000000 %s", aTaskNumber, aMaxTaskNumberPerNode, aCommand), aPartition, (int)Math.ceil(aTaskNumber/(double)aMaxTaskNumberPerNode), aOutputPath);
    }
    
    /**
     * 提供一个直接使用 srun 来执行脚本的接口，实际会使用 sbatch 将任务挂到后台
     * 输入本地的脚本路径，首先会将其上传到服务器对应位置
     * 与 sbatch 的脚本不同，这里不能在脚本中指定参数
     */
    public void submitSrunBash(String aBashPath                                                                                   ) {submitSrunBash(aBashPath, null);}
    public void submitSrunBash(String aBashPath,                    int aTaskNumber                                               ) {submitSrunBash(aBashPath, null, aTaskNumber);}
    public void submitSrunBash(String aBashPath,                    int aTaskNumber, int aMaxTaskNumberPerNode                    ) {submitSrunBash(aBashPath, null, aTaskNumber, aMaxTaskNumberPerNode);}
    public void submitSrunBash(String aBashPath,                    int aTaskNumber, int aMaxTaskNumberPerNode, String aOutputPath) {submitSrunBash(aBashPath, null, aTaskNumber, aMaxTaskNumberPerNode, aOutputPath);}
    public void submitSrunBash(String aBashPath, String aPartition                                                                ) {submitSrunBash(aBashPath, aPartition, 1);}
    public void submitSrunBash(String aBashPath, String aPartition, int aTaskNumber                                               ) {submitSrunBash(aBashPath, aPartition, aTaskNumber, 20);}
    public void submitSrunBash(String aBashPath, String aPartition, int aTaskNumber, int aMaxTaskNumberPerNode                    ) {submitSrunBash(aBashPath, aPartition, aTaskNumber, aMaxTaskNumberPerNode, ".temp/slurm/out-%j");}
    public void submitSrunBash(String aBashPath, String aPartition, int aTaskNumber, int aMaxTaskNumberPerNode, String aOutputPath) {submitSrunBash(null, aBashPath, aPartition, aTaskNumber, aMaxTaskNumberPerNode, aOutputPath);}
    
    public void submitSrunBash(Task aBeforeSystem, String aBashPath                                                                                   ) {submitSrunBash(aBeforeSystem, aBashPath, null);}
    public void submitSrunBash(Task aBeforeSystem, String aBashPath,                    int aTaskNumber                                               ) {submitSrunBash(aBeforeSystem, aBashPath, null, aTaskNumber);}
    public void submitSrunBash(Task aBeforeSystem, String aBashPath,                    int aTaskNumber, int aMaxTaskNumberPerNode                    ) {submitSrunBash(aBeforeSystem, aBashPath, null, aTaskNumber, aMaxTaskNumberPerNode);}
    public void submitSrunBash(Task aBeforeSystem, String aBashPath,                    int aTaskNumber, int aMaxTaskNumberPerNode, String aOutputPath) {submitSrunBash(aBeforeSystem, aBashPath, null, aTaskNumber, aMaxTaskNumberPerNode, aOutputPath);}
    public void submitSrunBash(Task aBeforeSystem, String aBashPath, String aPartition                                                                ) {submitSrunBash(aBeforeSystem, aBashPath, aPartition, 1);}
    public void submitSrunBash(Task aBeforeSystem, String aBashPath, String aPartition, int aTaskNumber                                               ) {submitSrunBash(aBeforeSystem, aBashPath, aPartition, aTaskNumber, 20);}
    public void submitSrunBash(Task aBeforeSystem, String aBashPath, String aPartition, int aTaskNumber, int aMaxTaskNumberPerNode                    ) {submitSrunBash(aBeforeSystem, aBashPath, aPartition, aTaskNumber, aMaxTaskNumberPerNode, ".temp/slurm/out-%j");}
    public void submitSrunBash(Task aBeforeSystem, String aBashPath, String aPartition, int aTaskNumber, int aMaxTaskNumberPerNode, String aOutputPath) {submitSrunBash(aBeforeSystem, null, aBashPath, aPartition, aTaskNumber, aMaxTaskNumberPerNode, aOutputPath);}
    
    public void submitSrunBash(Task aBeforeSystem, Task aAfterSystem, String aBashPath                                                                                   ) {submitSrunBash(aBeforeSystem, aAfterSystem, aBashPath, null);}
    public void submitSrunBash(Task aBeforeSystem, Task aAfterSystem, String aBashPath,                    int aTaskNumber                                               ) {submitSrunBash(aBeforeSystem, aAfterSystem, aBashPath, null, aTaskNumber);}
    public void submitSrunBash(Task aBeforeSystem, Task aAfterSystem, String aBashPath,                    int aTaskNumber, int aMaxTaskNumberPerNode                    ) {submitSrunBash(aBeforeSystem, aAfterSystem, aBashPath, null, aTaskNumber, aMaxTaskNumberPerNode);}
    public void submitSrunBash(Task aBeforeSystem, Task aAfterSystem, String aBashPath,                    int aTaskNumber, int aMaxTaskNumberPerNode, String aOutputPath) {submitSrunBash(aBeforeSystem, aAfterSystem, aBashPath, null, aTaskNumber, aMaxTaskNumberPerNode, aOutputPath);}
    public void submitSrunBash(Task aBeforeSystem, Task aAfterSystem, String aBashPath, String aPartition                                                                ) {submitSrunBash(aBeforeSystem, aAfterSystem, aBashPath, aPartition, 1);}
    public void submitSrunBash(Task aBeforeSystem, Task aAfterSystem, String aBashPath, String aPartition, int aTaskNumber                                               ) {submitSrunBash(aBeforeSystem, aAfterSystem, aBashPath, aPartition, aTaskNumber, 20);}
    public void submitSrunBash(Task aBeforeSystem, Task aAfterSystem, String aBashPath, String aPartition, int aTaskNumber, int aMaxTaskNumberPerNode                    ) {submitSrunBash(aBeforeSystem, aAfterSystem, aBashPath, aPartition, aTaskNumber, aMaxTaskNumberPerNode, ".temp/slurm/out-%j");}
    public void submitSrunBash(Task aBeforeSystem, Task aAfterSystem, String aBashPath, String aPartition, int aTaskNumber, int aMaxTaskNumberPerNode, String aOutputPath) {
        if (mDead) throw new RuntimeException("Can NOT submitSrunBash from a Dead SLURM.");
        // 需要上传脚本
        aBeforeSystem = mergeTask(aBeforeSystem, mSSH.task_putFile(aBashPath));
        // 提交命令
        submitSrun(aBeforeSystem, aAfterSystem, String.format("bash %s", aBashPath), aPartition, aTaskNumber, aMaxTaskNumberPerNode, aOutputPath);
    }
    
    
    // 获取这个用户正在执行的任务，和这个类本身无关
    public int jobNumber() throws JSchException, IOException {if (mDead) throw new RuntimeException("Can NOT get jobNumber from a Dead SLURM."); return jobIDs().size();}
    // 获取这个用户正在执行的任务 ID 的列表
    public Set<Integer> jobIDs() throws JSchException, IOException {if (mDead) throw new RuntimeException("Can NOT get jobIDs from a Dead SLURM."); return jobIDs_();}
    Set<Integer> jobIDs_() throws JSchException, IOException {
        // 组装指令
        String tCommand = String.format("squeue --noheader --user %s --format %%i", mSqueueName);
        // systemChannel 内部已经尝试了重连
        ChannelExec tChannelExec = mSSH.systemChannel(tCommand);
        // 获取输出得到任务数目
        InputStream tIn = tChannelExec.getInputStream();
        tChannelExec.connect();
        BufferedReader tReader = new BufferedReader(new InputStreamReader(tIn));
        Set<Integer> rJobIDs = new LinkedHashSet<>();
        String tLine;
        while ((tLine = tReader.readLine()) != null) rJobIDs.add(Integer.parseInt(tLine));
        // 最后关闭通道
        tChannelExec.disconnect();
        return rJobIDs;
    }
    
    // 取消这个用户所有的任务
    public Task task_cancelAll() {return new Task() {
        @Override public boolean run() throws Exception {cancelAll(); return true;}
        @Override public String toString() {return Type.CANCEL_ALL.name();}
    };}
    public void cancelAll() throws JSchException, IOException {
        if (mDead) throw new RuntimeException("Can NOT cancelAll from a Dead SLURM.");
        synchronized(mCommandList) {mCommandList.clear(); doMemberChange.run();}
        mSSH.system(String.format("scancel --user %s --full", mSqueueName));
        synchronized(mJobIDList) {mJobIDList.clear(); doMemberChange.run();}
    }
    
    // 取消这个对象一共提交的所有任务
    public Task task_cancelThis() {return new Task() {
        @Override public boolean run() throws Exception {cancelThis(); return true;}
        @Override public String toString() {return Type.CANCEL_THIS.name();}
    };}
    public void cancelThis() throws JSchException, IOException {
        if (mDead) throw new RuntimeException("Can NOT cancelThis from a Dead SLURM.");
        synchronized(mCommandList) {mCommandList.clear(); doMemberChange.run();}
        boolean tIsJobEmpty;
        synchronized(mJobIDList) {tIsJobEmpty = mJobIDList.isEmpty();}
        if (!tIsJobEmpty) {
            mSSH.system(String.format("scancel --name %s", mJobName));
            synchronized(mJobIDList) {mJobIDList.clear(); doMemberChange.run();}
        }
    }
    
    // 撤销上一步提交的任务（如果已经交上去则会失败）
    public Pair<Pair<Task, Task>, String> undo() {
        Pair<Pair<Task, Task>, String> tCommand;
        synchronized (mCommandList) {tCommand = mCommandList.pollLast(); doMemberChange.run();}
        return tCommand;
    }
    
    /// 提供 SystemThreadPool 的相关接口
    public int getActiveCount() {int tActiveCount; synchronized(mJobIDList) {tActiveCount = mJobIDList.size();} return tActiveCount;}
    public int getQueueSize() {int tQueueSize; synchronized(mCommandList) {tQueueSize = mCommandList.size();} return tQueueSize;}
    public void waitUntilDone() throws InterruptedException {while (getActiveCount() > 0 || getQueueSize() > 0) Thread.sleep(200);}
    public int getTaskNumber() {return getActiveCount() + getQueueSize();}
    
    public boolean awaitTermination() throws InterruptedException {return mPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);}
    public int[] getActiveJobIDs() {
        int[] tJobIDs;
        synchronized(mJobIDList) {
            tJobIDs = new int[mJobIDList.size()];
            int i = 0;
            for (int tJobID : mJobIDList.keySet()) {tJobIDs[i] = tJobID; ++i;}
        }
        return tJobIDs;
    }
    public String[] getQueueCommands() {
        String[] tCommands;
        synchronized(mCommandList) {
            tCommands = new String[mCommandList.size()];
            int i = 0;
            for (Pair<Pair<Task, Task>, String> tPair : mCommandList) {tCommands[i] = tPair.second; ++i;}
        }
        return tCommands;
    }
    
    
    /// 提供一些基本内部类
    // 容忍次数计数器，可以通过重写输入的 Runnable 来实现具体的操作
    static class TolerantCounter {
        private int mTolerant = DEFAULT_TOLERANT;
        private int mUsedTolerant = 0;
        
        public void call(boolean aSuc, String aPrint, Runnable doOverTolerant) {call(aSuc, aPrint, doOverTolerant, () -> {}, () -> {});}
        public void call(boolean aSuc, String aPrint, Runnable doOverTolerant, Runnable doUnsuccess, Runnable doSuccess) {
            if (!aSuc) {
                ++mUsedTolerant;
                if (mUsedTolerant > mTolerant) {
                    mUsedTolerant = 0;
                    doOverTolerant.run();
                    System.out.println("WARNING: Fail more than "+mTolerant+" times in "+aPrint);
                    System.out.flush();
                }
                doUnsuccess.run();
            } else {
                mUsedTolerant = 0;
                doSuccess.run();
            }
        }
    }
}
