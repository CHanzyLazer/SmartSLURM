import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpException;

import java.io.*;
import java.util.*;
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
    private final LinkedList<String> mCommandList = new LinkedList<>();
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
            // 首先获取正在执行的任务队列
            Set<Integer> tJobIDs = null;
            try {tJobIDs = jobIDs();} catch (JSchException | IOException ignored) {}
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
            String tCommand;
            synchronized (mCommandList) {tCommand = mCommandList.pollFirst();}
            // 提交任务
            int tJobID = -1;
            try {tJobID = submitSbatch_(tCommand);} catch (JSchException | IOException ignored) {}
            if (tJobID > 0) synchronized(mJobIDList) {mJobIDList.add(tJobID);}
        }
        // 最后关闭 SSH 通道
        mSSH.shutdown();
    }
    
    // 内部的提交任务接口，必须是 sbatch 的指令从而能够获取进程号，返回 -1 表示提交失败
    int submitSbatch_(String aSbatchCommand) throws JSchException, IOException {
        if (mDead) throw new RuntimeException("Can NOT submitSbatch from a Dead SLURM.");
        if (aSbatchCommand == null) return -1;
        // systemChannel 内部已经尝试了重连
        ChannelExec tChannelExec = mSSH.systemChannel(aSbatchCommand);
        // 获取输出从而得到任务号
        InputStream tIn = tChannelExec.getInputStream();
        tChannelExec.connect();
        BufferedReader tReader = new BufferedReader(new InputStreamReader(tIn));
        String tLine = tReader.readLine(); // 只需要读取一行
        tReader.close();
        // 最后关闭通道
        tChannelExec.disconnect();
        // 返回任务号
        if (tLine != null && tLine.startsWith("Submitted batch job ")) return Integer.parseInt(tLine.substring(20));
        return -1;
    }
    
    /**
     * 通用的提交任务接口，底层使用 sbatch 来提交任务
     * 只需要指定需要运行的目标分区以及总的需要的节点数目
     * 一般来说需要在指令中自己使用 srun 来开始并行任务
     * 可以指定输出目录
     */
    public void submitSystem(String aCommand) throws JSchException {submitSystem(aCommand, "work");}
    public void submitSystem(String aCommand, int aNodeNumber) throws JSchException {submitSystem(aCommand, "work", aNodeNumber);}
    public void submitSystem(String aCommand, int aNodeNumber, String aOutputPath) {submitSystem(aCommand, "work", aNodeNumber, aOutputPath);}
    public void submitSystem(String aCommand, String aPartition) throws JSchException {submitSystem(aCommand, aPartition, 1);}
    public void submitSystem(String aCommand, String aPartition, int aNodeNumber) throws JSchException {if (mDead) throw new RuntimeException("Can NOT submitSbatch from a Dead SLURM."); mSSH.mkdir(".temp/slurm"); submitSystem(aCommand, aPartition, aNodeNumber, ".temp/slurm/out-%j");}
    public void submitSystem(String aCommand, String aPartition, int aNodeNumber, String aOutputPath) {
        if (mDead) throw new RuntimeException("Can NOT submitSbatch from a Dead SLURM.");
        // 组装指令
        aCommand = String.format("echo -e '#!/bin/bash\\n%s' | sbatch --partition %s --nodes %d --output %s", aCommand, aPartition, aNodeNumber, aOutputPath);
        // 添加指令到队列
        synchronized (mCommandList) {mCommandList.addLast(aCommand);}
    }
    
    /**
     * 提供一个直接使用 srun 执行指令的接口，实际会使用 sbatch 将任务挂到后台
     * 输入具体的指令，分区，任务数目（并行数目），每节点的最多任务数（用于计算节点数目）
     * 可以指定输出目录
     */
    public void submitSrun(String aCommand) throws JSchException {submitSrun(aCommand, "work");}
    public void submitSrun(String aCommand, int aTaskNumber) throws JSchException {submitSrun(aCommand, "work", aTaskNumber);}
    public void submitSrun(String aCommand, int aTaskNumber, int aMaxTaskNumberPerNode) throws JSchException {submitSrun(aCommand, "work", aTaskNumber, aMaxTaskNumberPerNode);}
    public void submitSrun(String aCommand, int aTaskNumber, int aMaxTaskNumberPerNode, String aOutputPath) {submitSrun(aCommand, "work", aTaskNumber, aMaxTaskNumberPerNode, aOutputPath);}
    public void submitSrun(String aCommand, String aPartition) throws JSchException {submitSrun(aCommand, aPartition, 1);}
    public void submitSrun(String aCommand, String aPartition, int aTaskNumber) throws JSchException {submitSrun(aCommand, aPartition, aTaskNumber, 20);}
    public void submitSrun(String aCommand, String aPartition, int aTaskNumber, int aMaxTaskNumberPerNode) throws JSchException {if (mDead) throw new RuntimeException("Can NOT submitSbatch from a Dead SLURM."); mSSH.mkdir(".temp/slurm"); submitSrun(aCommand, aPartition, aTaskNumber, aMaxTaskNumberPerNode, ".temp/slurm/out-%j");}
    public void submitSrun(String aCommand, String aPartition, int aTaskNumber, int aMaxTaskNumberPerNode, String aOutputPath) {
        submitSystem(String.format("srun --ntasks %d --ntasks-per-node %d %s", aTaskNumber, aMaxTaskNumberPerNode, aCommand), aPartition, (int)Math.ceil(aTaskNumber/(double)aMaxTaskNumberPerNode), aOutputPath);
    }
    
    /**
     * 提供一个直接使用 srun 来执行脚本的接口，实际会使用 sbatch 将任务挂到后台
     * 输入本地的脚本路径，首先会将其上传到服务器对应位置
     * 主要是个人用途，需要使用脚本来突破同时进行的任务数目
     */
    public void submitSrunBash(String aBashPath) throws JSchException {submitSrunBash(aBashPath, "work");}
    public void submitSrunBash(String aBashPath, int aTaskNumber) throws JSchException {submitSrunBash(aBashPath, "work", aTaskNumber);}
    public void submitSrunBash(String aBashPath, int aTaskNumber, int aMaxTaskNumberPerNode) throws JSchException {submitSrunBash(aBashPath, "work", aTaskNumber, aMaxTaskNumberPerNode);}
    public void submitSrunBash(String aBashPath, int aTaskNumber, int aMaxTaskNumberPerNode, String aOutputPath) throws JSchException {submitSrunBash(aBashPath, "work", aTaskNumber, aMaxTaskNumberPerNode, aOutputPath);}
    public void submitSrunBash(String aBashPath, String aPartition) throws JSchException {submitSrunBash(aBashPath, aPartition, 1);}
    public void submitSrunBash(String aBashPath, String aPartition, int aTaskNumber) throws JSchException {submitSrunBash(aBashPath, aPartition, aTaskNumber, 20);}
    public void submitSrunBash(String aBashPath, String aPartition, int aTaskNumber, int aMaxTaskNumberPerNode) throws JSchException {if (mDead) throw new RuntimeException("Can NOT submitSbatch from a Dead SLURM."); mSSH.mkdir(".temp/slurm"); submitSrunBash(aBashPath, aPartition, aTaskNumber, aMaxTaskNumberPerNode, ".temp/slurm/out-%j");}
    public void submitSrunBash(String aBashPath, String aPartition, int aTaskNumber, int aMaxTaskNumberPerNode, String aOutputPath) throws JSchException {
        if (mDead) throw new RuntimeException("Can NOT submitSbatch from a Dead SLURM.");
        // 先上传脚本
        // 会尝试一次重新连接
        if (!mSSH.isConnecting()) mSSH.connect();
        // 获取文件传输通道
        ChannelSftp tChannelSftp = (ChannelSftp) mSSH.mSession.openChannel("sftp");
        tChannelSftp.connect();
        // 检测文件路径是否合法
        File tLocalFile = new File(mSSH.mLocalWorkingDir+aBashPath);
        if (!tLocalFile.isFile()) {System.out.println("Invalid Bash Path: "+aBashPath); return;}
        // 创建目标文件夹
        String tRemoteDir = mSSH.mRemoteWorkingDir;
        int tEndIdx = aBashPath.lastIndexOf("/");
        if (tEndIdx > 0) {
            tRemoteDir += aBashPath.substring(0, tEndIdx+1);
            ServerSSH.mkdir_(tChannelSftp, tRemoteDir); // 否则不用上传，认为 mRemoteWorkingDir 已经存在
        }
        // 上传脚本
        try {tChannelSftp.put(tLocalFile.getPath(), tRemoteDir);} catch (SftpException e) {return;}
        // 最后关闭通道
        tChannelSftp.disconnect();
        // 提交命令
        submitSrun(String.format("bash %s", aBashPath), aPartition, aTaskNumber, aMaxTaskNumberPerNode, aOutputPath);
    }
    
    
    // 获取这个用户正在执行的任务，和这个类本身无关
    public int jobNumber() throws JSchException, IOException {if (mDead) throw new RuntimeException("Can NOT get jobNumber from a Dead SLURM."); return jobIDs().size();}
    // 获取这个用户正在执行的任务 ID 的列表
    public Set<Integer> jobIDs() throws JSchException, IOException {
        if (mDead) throw new RuntimeException("Can NOT get jobIDs from a Dead SLURM.");
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
    public String undo() {
        String tCommand;
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
