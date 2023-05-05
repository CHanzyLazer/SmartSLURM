%% load smartSLURM
clear;
javaaddpath('include/smartSLURM.jar');

%% set login parameters
SSH_USER = 'username';
SSH_IP = 'hostname';
SSH_PORT = 22;
SSH_PASSWORD = 'password';
SERVER_PROJECT_PATH = '~/test-smartSLURM';

%% SSH PART
import com.chanzy.*
% init ssh, remote project path is needed
ssh = ServerSSH.get(SERVER_PROJECT_PATH, SSH_USER, SSH_IP, SSH_PORT, SSH_PASSWORD);
% put all of this project dir to the server (will exclude file and dir which begin with '.' or '_')
ssh.putWorkingDir();
% can mkdir in ssh
ssh.mkdir('.test1/.test2/.test3');
% submit command to remote server by using 'system' like matlab
ssh.system('ls');
ssh.system('echo "I am using smartSlURM system" > .test1/.test2/.test3/out.txt');
% get dir from remote server by using 'getDir'
ssh.getDir('.test1');
ssh.getDir('.test1', 4); % using 4 threads
% set compression level for large file transmission
ssh.setCompressionLevel(4);
ssh.putDir('.test1');
% unset compression by setting a negative level
ssh.setCompressionLevel(-1);
% you can get the pool to submit commands parallel
pool = ssh.pool(4);
pool.submitSystem('sleep 1s');
pool.submitSystem('sleep 2s');
pool.submitSystem('sleep 3s');
pool.submitSystem('sleep 4s');
pool.submitSystem('sleep 5s');
tic; pool.waitUntilDone(); toc;
pool.shutdown();
% use 'shutdown()' to disconnect at last
ssh.shutdown();

%% SLURM PART
import com.chanzy.*
% init slurm, max job number, remote project path is needed
slurm = ServerSLURM.get(4, SERVER_PROJECT_PATH, SSH_USER, SSH_IP, SSH_PORT, SSH_PASSWORD);
% you can get the ssh of this and use the ssh methods before
ssh = slurm.ssh();
% use 'submitSystem' to submit command in sbatch mod
% slurm.submitSystem(Command, [Partition='work'], NodeNumber=1, OutputPath='.temp/slurm/out-%j');
slurm.submitSystem('echo "I am using smartSlURM submitSystem"');
% use 'submitSrun' to submit a srun command directly (also in sbatch mod)
% slurm.submitSrun(Command, [Partition='work'], TaskNumber=1, MaxTaskNumberPerNode=20, OutputPath='.temp/slurm/out-%j');
slurm.submitSrun('echo "I am using smartSlURM submitSrun"');
% use 'submitSrunBash' to submit a bash file and use srun to exec (also in sbatch mod)
% slurm.submitSrunBash(BashPath, [Partition='work'], TaskNumber=1, MaxTaskNumberPerNode=20, OutputPath='.temp/slurm/out-%j');
slurm.submitSrun('path/to/your/script');
% you can use 'undo' to poll the last command in queue list
cmd = slurm.undo();
% use 'getActiveCount' and 'getQueueSize' to monitor task situation
activeNum = slurm.getActiveCount();
queueNum = slurm.getQueueSize();
taskNum = slurm.getTaskNumber(); % getActiveCount() + getQueueSize();
% you can use `waitUntilDone` to wait all task complete
slurm.waitUntilDone();
% or can use the classic way in java thread pool
slurm.shutdown();
slurm.awaitTermination();

%% unload smartSLURM
clear;
javarmpath('include/smartSLURM.jar');
