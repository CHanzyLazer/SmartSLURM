%% load smartSLURM
clear;
javaaddpath('include/smartSLURM.jar');

%% set login parameters
SSH_USER = 'username';
SSH_IP = 'hostname';
SERVER_PROJECT_PATH = '~/test-smartSLURM';

%% SLURM PART
import com.chanzy.*
% init slurm in this way is also ok, use public key
slurm = ServerSLURM.get(2, SERVER_PROJECT_PATH, SSH_USER, SSH_IP);
% set mirror
slurm.setMirror('slurm.json');
% submit jobs will be synchronized to the mirror
slurm.submitSystem('sleep 10s');
slurm.submitSystem('sleep 10s');
slurm.submitSystem('sleep 10s');
% load the mirro directly like this way is NOT recommended, and a warning will be output
% slurm = ServerSLURM.load('slurm.json');
% you need to kill the old slurm first
slurm.kill();
slurm = ServerSLURM.load('slurm.json');
% use the classic way in java thread pool to wait Termination
slurm.shutdown();
slurm.awaitTermination();

%% unload smartSLURM
clear;
javarmpath('include/smartSLURM.jar');
