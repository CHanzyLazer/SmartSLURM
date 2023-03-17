import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpException;

import java.io.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author CHanzy
 * 对于 SLURM 系统的服务器提供专门的接口方便任务提交
 * 与 SSH 不同，可以把本身当作一个 SystemThreadPool
 * 相比 SSH 多一个 mMaxJobNumber 参数限制同时运行的任务数
 */
@SuppressWarnings("unused")
public final class ServerSLURM {
    private final ServerSSH mSSH;
    private final int mMaxJobNumber;
    
    private final Set<Integer> mJobIDList = new LinkedHashSet<>();
    private final LinkedList<Pair<Callable<Boolean>, String>> mCommandList = new LinkedList<>();
    private final ExecutorService mPool;
    private boolean mDead = false;
    
    /// 构造函数以及获取方式（用来区分私钥登录以及密码登录）
    private ServerSLURM(ServerSSH aSSH, int aMaxThreadNumber) {
        mSSH = aSSH;
        mMaxJobNumber = aMaxThreadNumber;
        // 初始化线程池
        mPool = Executors.newSingleThreadExecutor();
        // 提交长期任务，不断从 mCmdList 获取指令并执行
        mPool.execute(this::keepSubmitFromList_);
    }
    // 不提供密码则认为是私钥登录，提供密码则认为是密码登录
    public static ServerSLURM get(int aMaxJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname) throws JSchException {return new ServerSLURM(ServerSSH.get(aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname), aMaxJobNumber);}
    public static ServerSLURM get(int aMaxJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname, int aPort) throws JSchException {return new ServerSLURM(ServerSSH.get(aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname, aPort), aMaxJobNumber);}
    public static ServerSLURM get(int aMaxJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname, String aPassword) throws JSchException {return new ServerSLURM(ServerSSH.get(aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname, aPassword), aMaxJobNumber);}
    public static ServerSLURM get(int aMaxJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname, int aPort, String aPassword) throws JSchException {return new ServerSLURM(ServerSSH.get(aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname, aPort, aPassword), aMaxJobNumber);}
    public static ServerSLURM getKey(int aMaxJobNumber, String aLocalWorkingDir, String aRemoteWorkingDir, String aUsername, String aHostname, int aPort, String aKeyPath) throws JSchException {return new ServerSLURM(ServerSSH.getKey(aLocalWorkingDir, aRemoteWorkingDir, aUsername, aHostname, aPort, aKeyPath), aMaxJobNumber);}
    
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
    
    /// 内部实用类
    // 内部的从队列中提交任务
    void keepSubmitFromList_() {
        while (true) {
            // 由于检测任务是否完成也需要发送指令，简单起见这里直接限制提交频率为 0.5s 一次
            try {Thread.sleep(500);} catch (InterruptedException e) {e.printStackTrace(); break;}
            // 从队列中获取指令
            boolean tIsCoCommandEmpty;
            synchronized (mCommandList) {tIsCoCommandEmpty = mCommandList.isEmpty();}
            // 如果没有指令需要提交，并且没有正在执行的任务则需要考虑关闭线程
            boolean tIsJobEmpty;
            synchronized(mJobIDList) {tIsJobEmpty = mJobIDList.isEmpty();}
            if (tIsCoCommandEmpty && tIsJobEmpty) {if (mDead) break; else continue;}
            // 这里统一检查一次联机状态，如果重新连接失败直接跳过重试
            if (!mSSH.isConnecting()) try {mSSH.connect();} catch (JSchException e) {continue;}
            // 首先获取正在执行的任务队列
            Set<Integer> tJobIDs = null;
            try {tJobIDs = jobIDs_();} catch (JSchException | IOException ignored) {}
            // 获取失败则直接跳过重试
            if (tJobIDs == null) continue;
            // 首先更新正在执行的任务列表
            if (!tIsJobEmpty) synchronized(mJobIDList) {
                Iterator<Integer> tIt = mJobIDList.iterator();
                while (tIt.hasNext()) if (!tJobIDs.contains(tIt.next())) tIt.remove();
            }
            // 准备提交任务，如果没有任务则跳过
            if (tIsCoCommandEmpty) continue;
            // 如果正在执行的任务列表超过限制，则不会提交
            if (tJobIDs.size() >= mMaxJobNumber) continue;
            // 获取任务
            Pair<Callable<Boolean>, String> tCommand;
            synchronized (mCommandList) {tCommand = mCommandList.pollFirst();}
            if (tCommand == null) continue;
            // 提交任务
            Callable<Boolean> tBeforeSystem = tCommand.first;
            if (tBeforeSystem != null) {
                boolean tSuc;
                try {tSuc = tBeforeSystem.call();} catch (Exception e) {continue;}
                if (!tSuc) continue;
            }
            ChannelExec tChannelExec;
            try {tChannelExec = mSSH.systemChannel(tCommand.second);} catch (JSchException e) {continue;}
            int tJobID = getJobIDFromChannel_(tChannelExec);
            if (tJobID > 0) synchronized(mJobIDList) {mJobIDList.add(tJobID);}
        }
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
    
    /**
     * 通用的提交任务接口，底层使用 sbatch 来提交任务
     * 只需要指定需要运行的目标分区以及总的需要的节点数目
     * 一般来说需要在指令中自己使用 srun 来开始并行任务
     * 可以指定输出目录
     */
    public void submitSystem(String aCommand) {submitSystem(aCommand, "work");}
    public void submitSystem(String aCommand, int aNodeNumber) {submitSystem(aCommand, "work", aNodeNumber);}
    public void submitSystem(String aCommand, int aNodeNumber, String aOutputPath) {submitSystem(aCommand, "work", aNodeNumber, aOutputPath);}
    public void submitSystem(String aCommand, String aPartition) {submitSystem(aCommand, aPartition, 1);}
    public void submitSystem(String aCommand, String aPartition, int aNodeNumber) {submitSystem(aCommand, aPartition, aNodeNumber, ".temp/slurm/out-%j");}
    public void submitSystem(String aCommand, String aPartition, int aNodeNumber, String aOutputPath) {submitSystem(null, aCommand, aPartition, aNodeNumber, aOutputPath);}
    public void submitSystem(Callable<Boolean> aBeforeSystem, String aCommand, String aPartition, int aNodeNumber, String aOutputPath) {
        if (mDead) throw new RuntimeException("Can NOT submitSbatch from a Dead SLURM.");
        // 需要创建输出目录的文件夹
        int tEndIdx = aOutputPath.lastIndexOf("/");
        if (tEndIdx > 0) { // 否则不用创建，认为 mRemoteWorkingDir 已经存在
            final String tOutputDir = aOutputPath.substring(0, tEndIdx+1);
            final Callable<Boolean> tMkdirOutput = () -> mSSH.mkdir(tOutputDir);
            // 如果 aBeforeSystem 为空则替换，不为空则将其附加在 BeforeSystem 之后
            if (aBeforeSystem == null) {
                aBeforeSystem = tMkdirOutput;
            } else {
                final Callable<Boolean> tBeforeSystem = aBeforeSystem;
                aBeforeSystem = () -> tBeforeSystem.call() && tMkdirOutput.call();
            }
        }
        // 组装指令
        aCommand = String.format("echo -e '#!/bin/bash\\n%s' | sbatch --partition %s --nodes %d --output %s", aCommand, aPartition, aNodeNumber, aOutputPath);
        // 添加指令到队列
        synchronized (mCommandList) {mCommandList.addLast(new Pair<>(aBeforeSystem, aCommand));}
    }
    
    /**
     * 提供一个直接使用 srun 执行指令的接口，实际会使用 sbatch 将任务挂到后台
     * 输入具体的指令，分区，任务数目（并行数目），每节点的最多任务数（用于计算节点数目）
     * 可以指定输出目录
     */
    public void submitSrun(String aCommand) {submitSrun(aCommand, "work");}
    public void submitSrun(String aCommand, int aTaskNumber) {submitSrun(aCommand, "work", aTaskNumber);}
    public void submitSrun(String aCommand, int aTaskNumber, int aMaxTaskNumberPerNode) {submitSrun(aCommand, "work", aTaskNumber, aMaxTaskNumberPerNode);}
    public void submitSrun(String aCommand, int aTaskNumber, int aMaxTaskNumberPerNode, String aOutputPath) {submitSrun(aCommand, "work", aTaskNumber, aMaxTaskNumberPerNode, aOutputPath);}
    public void submitSrun(String aCommand, String aPartition) {submitSrun(aCommand, aPartition, 1);}
    public void submitSrun(String aCommand, String aPartition, int aTaskNumber) {submitSrun(aCommand, aPartition, aTaskNumber, 20);}
    public void submitSrun(String aCommand, String aPartition, int aTaskNumber, int aMaxTaskNumberPerNode) {submitSrun(aCommand, aPartition, aTaskNumber, aMaxTaskNumberPerNode, ".temp/slurm/out-%j");}
    public void submitSrun(String aCommand, String aPartition, int aTaskNumber, int aMaxTaskNumberPerNode, String aOutputPath) {submitSrun(null, aCommand, aPartition, aTaskNumber, aMaxTaskNumberPerNode, aOutputPath);}
    public void submitSrun(Callable<Boolean> aBeforeSystem, String aCommand, String aPartition, int aTaskNumber, int aMaxTaskNumberPerNode, String aOutputPath) {
        submitSystem(aBeforeSystem, String.format("srun --ntasks %d --ntasks-per-node %d %s", aTaskNumber, aMaxTaskNumberPerNode, aCommand), aPartition, (int)Math.ceil(aTaskNumber/(double)aMaxTaskNumberPerNode), aOutputPath);
    }
    
    /**
     * 提供一个直接使用 srun 来执行脚本的接口，实际会使用 sbatch 将任务挂到后台
     * 输入本地的脚本路径，首先会将其上传到服务器对应位置
     * 主要是个人用途，需要使用脚本来突破同时进行的任务数目
     */
    public void submitSrunBash(String aBashPath) {submitSrunBash(aBashPath, "work");}
    public void submitSrunBash(String aBashPath, int aTaskNumber) {submitSrunBash(aBashPath, "work", aTaskNumber);}
    public void submitSrunBash(String aBashPath, int aTaskNumber, int aMaxTaskNumberPerNode) {submitSrunBash(aBashPath, "work", aTaskNumber, aMaxTaskNumberPerNode);}
    public void submitSrunBash(String aBashPath, int aTaskNumber, int aMaxTaskNumberPerNode, String aOutputPath) {submitSrunBash(aBashPath, "work", aTaskNumber, aMaxTaskNumberPerNode, aOutputPath);}
    public void submitSrunBash(String aBashPath, String aPartition) {submitSrunBash(aBashPath, aPartition, 1);}
    public void submitSrunBash(String aBashPath, String aPartition, int aTaskNumber) {submitSrunBash(aBashPath, aPartition, aTaskNumber, 20);}
    public void submitSrunBash(String aBashPath, String aPartition, int aTaskNumber, int aMaxTaskNumberPerNode) {submitSrunBash(aBashPath, aPartition, aTaskNumber, aMaxTaskNumberPerNode, ".temp/slurm/out-%j");}
    public void submitSrunBash(final String aBashPath, String aPartition, int aTaskNumber, int aMaxTaskNumberPerNode, String aOutputPath) {
        if (mDead) throw new RuntimeException("Can NOT submitSbatch from a Dead SLURM.");
        // 先上传脚本
        Callable<Boolean> tUploadBash = () -> {
            // 会尝试一次重新连接
            if (!mSSH.isConnecting()) mSSH.connect();
            // 获取文件传输通道
            ChannelSftp tChannelSftp = (ChannelSftp) mSSH.mSession.openChannel("sftp");
            tChannelSftp.connect();
            // 检测文件路径是否合法
            File tLocalFile = new File(mSSH.mLocalWorkingDir+aBashPath);
            if (!tLocalFile.isFile()) {System.out.println("Invalid Bash Path: "+aBashPath); return false;}
            // 创建目标文件夹
            String tRemoteDir = mSSH.mRemoteWorkingDir;
            int tEndIdx = aBashPath.lastIndexOf("/");
            if (tEndIdx > 0) { // 否则不用创建，认为 mRemoteWorkingDir 已经存在
                tRemoteDir += aBashPath.substring(0, tEndIdx+1);
                if (!ServerSSH.isDir_(tChannelSftp, tRemoteDir) && !ServerSSH.mkdir_(tChannelSftp, tRemoteDir)) return false;
            }
            // 上传脚本
            tChannelSftp.put(tLocalFile.getPath(), tRemoteDir);
            // 最后关闭通道
            tChannelSftp.disconnect();
            return true;
        };
        // 提交命令
        submitSrun(tUploadBash, String.format("bash %s", aBashPath), aPartition, aTaskNumber, aMaxTaskNumberPerNode, aOutputPath);
    }
    
    
    // 获取这个用户正在执行的任务，和这个类本身无关
    public int jobNumber() throws JSchException, IOException {if (mDead) throw new RuntimeException("Can NOT get jobNumber from a Dead SLURM."); return jobIDs().size();}
    // 获取这个用户正在执行的任务 ID 的列表
    public Set<Integer> jobIDs() throws JSchException, IOException {if (mDead) throw new RuntimeException("Can NOT get jobIDs from a Dead SLURM."); return jobIDs_();}
    Set<Integer> jobIDs_() throws JSchException, IOException {
        // 组装指令
        String tCommand = String.format("squeue --noheader --user %s --format %%i", mSSH.mSession.getUserName());
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
    public void cancelAll() throws JSchException, IOException {
        if (mDead) throw new RuntimeException("Can NOT cancelAll from a Dead SLURM.");
        synchronized(mCommandList) {mCommandList.clear();}
        mSSH.system(String.format("scancel --user %s --full", mSSH.mSession.getUserName()));
        synchronized(mJobIDList) {mJobIDList.clear();}
    }
    
    // 需要这个对象一共提交的所有任务
    public void cancelThis() throws JSchException, IOException {
        if (mDead) throw new RuntimeException("Can NOT cancelThis from a Dead SLURM.");
        synchronized(mCommandList) {mCommandList.clear();}
        boolean tIsJobEmpty;
        synchronized(mJobIDList) {tIsJobEmpty = mJobIDList.isEmpty();}
        if (!tIsJobEmpty) {
            // 组装指令
            String tCommand = mJobIDList.stream().map(String::valueOf).collect(Collectors.joining(","));
            tCommand = "scancel " + tCommand;
            mSSH.system(tCommand);
            synchronized(mJobIDList) {mJobIDList.clear();}
        }
    }
    
    // 撤销上一步提交的任务（如果已经交上去则会失败）
    public Pair<Callable<Boolean>, String> undo() {
        Pair<Callable<Boolean>, String> tCommand;
        synchronized (mCommandList) {tCommand = mCommandList.pollLast();}
        return tCommand;
    }
    
    /// 提供 SystemThreadPool 的相关接口
    public int getActiveCount() {int tActiveCount; synchronized(mJobIDList) {tActiveCount = mJobIDList.size();} return tActiveCount;}
    public int getQueueSize() {int tQueueSize; synchronized(mCommandList) {tQueueSize = mCommandList.size();} return tQueueSize;}
    public void waitUntilDone() throws InterruptedException {while (getActiveCount() > 0 || getQueueSize() > 0) Thread.sleep(200);}
    public int getTaskNumber() {return getActiveCount() + getQueueSize();}
    
    public boolean awaitTermination() throws InterruptedException {return mPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);}
    public int[] getActiveJobIDs() {int[] tJobIDs; synchronized(mJobIDList) {tJobIDs = mJobIDList.stream().mapToInt(Integer::intValue).toArray();} return tJobIDs;}
    public String[] getQueueCommands() {String[] tCommands; synchronized(mCommandList) {tCommands = mCommandList.toArray(new String[0]);} return tCommands;}
}
