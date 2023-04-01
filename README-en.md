**切换到 [中文](README.md)**

# SmartSLURM
A SLURM controler make you use SLURM like a local application, 
which make it easy to handle workflows such as:

$$
\begin{array}{c}
\text{Generate input files} \longrightarrow
\text{Upload input files to server} \longrightarrow
\text{Submit jobs} \longrightarrow \\
\text{Wait for jobs to complete} \longrightarrow
\text{Download output files from server} \longrightarrow \\
\text{Process output files} \longrightarrow
\text{Generate next batch of input files based on the results} \longrightarrow \cdots
\end{array}
$$

This program consists of two parts: 
[ServerSSH](include/java/src/com/chanzy/ServerSSH.java) which contains basic methods for ssh servers, 
[ServerSLURM](include/java/src/com/chanzy/ServerSLURM.java) which is specifically adapted for servers that support the SLURM system.

The program is written in Java and uses [jsch](http://www.jcraft.com/jsch/) to connect to ssh servers and implement basic functions. 
It also adds some utility functions and optimizated the file transmission.

This program does not require any third-party libraries so it can be used directly.
[demo.m](demo.m) is a basic example for Matlab usage, and [demo.ipynb](demo.ipynb) is a basic example for Python usage.

# Usage
Download from [Release](https://github.com/CHanzyLazer/SmartSLURM/releases/latest). 
`SmartSLURM-with-demo.zip` contains runnable examples, and `smartSLURM.jar` is the jar package only.

In Matlab, you need to import the java classes first. 
In this example, the jar package is located in the include directory:
```matlab
javaaddpath('include/smartSLURM.jar');
```
The software package is `com.chanzy.*`. You can import it first:
```matlab
import com.chanzy.*
```
Finally, remove the java classes by using:
```matlab
clear;
javarmpath('include/smartSLURM.jar');
```

In Python, you need to use a third-party library to use java classes. 
Here we use [py4j](https://www.py4j.org/):
```python
from py4j.java_gateway import JavaGateway
GATEWAY = JavaGateway.launch_gateway(classpath='include/smartSLURM.jar')
ServerSSH = GATEWAY.jvm.com.chanzy.ServerSSH
ServerSLURM = GATEWAY.jvm.com.chanzy.ServerSLURM
```
Finally, simply close the GATEWAY:
```python
GATEWAY.shutdown()
```
Below we use Matlab code to demonstrate.

## Quick Start
To implement the workflow mentioned above, you can use the following script in Matlab:
```matlab
% Import the jar package
javaaddpath('include/smartSLURM.jar');
% Import the package
import com.chanzy.*
% Get the instance of slurm
slurm = ServerSLURM.get(8, 'path/to/project/in/local', 'path/to/project/in/remote', 'username', 'hostname', 'password');
% Upload input directory to the remote server and specify 4 concurrent threads for uploading (optional)
slurm.ssh().putDir('path/to/in/file/dir', 4);
% Submit njobs srun jobs, each job use 40 cores, and each node has a maximum of 20 cores (it will be executed in the default partition if no partition set)
for i = 1:njobs
    slurm.submitSrun('software/in/remote < path/to/in/file-' + num2str(i), 40, 20);
end
% Wait for the jobs to complete
slurm.waitUntilDone();
% Also supports the waitPools function in Matlab-JavaThreadPool
% waitPools(slurm);
% Also supports the commonly used methods of the java thread pool. (Although slurm cannot shutdown yet)
% slurm.shutdown();
% slurm.awaitTermination();
% Download output directory from the server. You can set the compression level to speed up the transmission for large output file.
slurm.ssh().setCompressionLevel(4);
slurm.ssh().getDir('path/to/out/file/dir', 4);
slurm.ssh().setCompressionLevel(-1); % Cancel data compression after transmission
% Data processing
% ...
% Submit the next batch of input files. Before this, it is usually to clear the old input files.
slurm.ssh().clearDir('path/to/in/file/dir');
slurm.ssh().putDir('path/to/in/file/dir', 4);
% ...
```

## Advanced Usage
There may be some advanced needs:
- Jobs may take a very long time so slurm instance need to support for save and load.
- Upload and download from server may fail so it is required to repeat.
- Upload and download may take too long, and you don't want to block the main thread.

For long time jobs, smartSLURM supports `setMirror` function,
which can set a local mirror file that can be reloaded later to continue the jobs:
```matlab
% Set local mirror
slurm.setMirror('path/to/loacl/mirror');
% Submit jobs as usual
for i = 1:njobs
    slurm.submitSrun('software/in/remote < path/to/in/file-' + num2str(i), 40, 20);
end
% Wait for a while for jobs to be submitted
pause(60);
% You can also use getQueueSize to check the number of jobs in the queue and wait for them
% while slurm.getQueueSize() > 0
%     pause(10);
% end
% Then you can kill the slurm and do other jobs
slurm.kill();
% ...
% To check if the jobs have finished, reload the mirror and see if there are any jobs left
slurm = ServerSLURM.load('path/to/loacl/mirror');
if slurm.getTaskNumber() > 0
    disp('Jobs not finish yet.');
else
    disp('Jobs finished.');
    % Download output directory and do other operations if need
    slurm.ssh().getDir('path/to/out/file/dir', 4);
    % ...
end
```
Notes:
- You do NOT have to wait for all the queued jobs to be submitted before killing the slurm. 
The mirror file also records queued jobs, and the killed slurm will just don't submit the queued jobs. 
The queued jobs will be automatically submited after the mirror is reloaded.
- You MUST kill the old slurm before loading the new one, otherwise there may be redundant submit, 
which the program will detect and issue a warning to remind you.
- Since job submission is executed in another thread, if you kill the slurm immediately after submited the job with `submitSrun`,
these jobs will still be queued.

For the issue of upload and download, smartSLURM supports packaging basic operations into `Task` objects, 
which can be attached to the submitted job and executed together:
```matlab
% Get the task of uploading input directory before submit the job
taskBefore = slurm.ssh().task_putDir('path/to/in/file/dir', 4);
% Get the task for downloading output directory after submit the job
taskAfter = slurm.ssh().task_getDir('path/to/out/file/dir', 4);
% Submit the job and attach these two tasks
for i = 1:njobs
    slurm.submitSrun(taskBefore, taskAfter, 'software/in/remote < path/to/in/file-' + num2str(i), 40, 20);
end
% Wait for the job to complete...
% ...
```
In this way, slurm will automatically run `taskBefore` before submit the `srun` command, and run `taskAfter` after `srun` is completed. 
Moreover, slurm has built-in retrying functionality for these operations
(retry 3 times by default, can be modified using `setTolerant`).

Notes:
- You can submit `taskBefore` only:

```matlab
slurm.submitSrun(taskBefore, 'software/in/remote < path/to/in/file', 40, 20);
```
- Multiple tasks can be merged by using `mergeTask`:
```matlab
task1 = slurm.ssh().task_system('python dataPreprocessing.py path/to/out/file/dir');
task2 = slurm.ssh().task_getDir('path/to/out/file/dir', 4);
taskAfter = code.UT.mergeTask(task1, task2); % Including preprocessing data using a Python script on the remote server and downloading the processed data
```
- Task can be run directly in matlab:
```matlab
task = slurm.ssh().task_putDir('path/to/out/file/dir', 4);
task.run(); % Equivalent to "slurm.ssh().putDir('path/to/out/file/dir', 4);"
% Can also be run with the option to retry
suc = code.UT.tryTask(task, 3);
% Equivalent to
% suc = false;
% for i = 1:3
%     suc = code.UT.tryTask(task);
%     if suc; break; end
% end
```


# Interface Description
- **`ServerSSH`**: 
Provides basic methods for managing SSH servers, 
and all operations will attempt to reconnect once when connection is interrupted.
    - **Save and Load**
        - `save(FilePath)`: 
        Save this ssh instance to `FilePath` in json format. 
        **Note that the save file is not encrypted. If there is a password, it will be saved in plain text.**
        **You need to keep the file safe. If you are concerned, you can use public key authentication.**
        - `load(FilePath)`: 
        Static method. Load ssh instance from `FilePath`.
    - **Get Instance**
        - `get(LocalWorkingDir, RemoteWorkingDir, Username, Hostname, [Port=22], [Password])`: 
        Static method. Get the ssh terminal instance and connect to the remote server. 
        Key authentication will be used if no password is provided. 
        The default key location is `{user.home}/.ssh/id_rsa`.
        - `getKey(LocalWorkingDir, RemoteWorkingDir, Username, Hostname, [Port=22], KeyPath)`: 
        Static method. Get the ssh terminal instance and connect to the remote server. 
        Authenticate using the key at `KeyPath`. 
        **Note that JSch only supports classic format openSSH key, so flag `-m pem` must be added when generating keys.**
    - **Parameter Settings**
        - `setLocalWorkingDir(LocalWorkingDir)`: 
        Set the local working directory. 
        It will be set to the system user path `System.getProperty("user.home")` if null or an empty string is entered.
        - `setRemoteWorkingDir(RemoteWorkingDir)`: 
        Set the remote server's working directory. 
        It will be set to the default path when connecting to ssh if null or an empty string is entered.
        - `setCompressionLevel(CompressionLevel)`: 
        Set the compression level (1-9) used for ssh transfer. 
        Setting a value less than or equal to 0 will turn off compression. No compression in default.
        - `setBeforeSystem(Command)`: 
        Set the command that will always be appended before executing the `system` command, 
        such as setting environment variables, etc.
        - `setPassword(Password)`: 
        Modify the login password and change the authentication mode to password.
        - `setKey(KeyPath)`:
         Modify the key path and change the authentication mode to public key.
    - **Basic Methods**
        - `isConnecting()`: 
        Checks whether the SSH connection is being maintained.
        - `connect()`: 
        If is disconnected, it will be reconnected. 
        This method does not need to be called manually because most methods will attempt to reconnect once.
        - `disconnect()`: 
        Manually disconnect the ssh connection. 
        This method does not need to be called manually because most methods will attempt to reconnect once.
        - `shutdown()`: 
        Disconnects and closes the ssh terminal. No further operations are allowed.
        - `session()`: 
        Return the current ssh session. 
        This method does not need to be called manually because most commonly used functions have already been wrapped.
    - **Practical Methods**
        - `[task_]system(Command)`: 
        Execute a command on the ssh terminal, similar to the `system` function in Matlab. 
        You can add `task_` to get a `Task` object of this method (same for following methods).
        - `[task_]putDir(Dir, [ThreadNumber])`: 
        Upload directory `Dir` to the remote server. 
        Setting `ThreadNumber` will enable concurrent uploading. 
        Note that setting `ThreadNumber=1` is not equivalent to not setting it. 
        Support upload subfolders recursively. 
        Compression can be enabled with `setCompressionLevel(CompressionLevel)`.
        - `[task_]getDir(Dir, [ThreadNumber])`: 
        Download the directory `Dir` from the remote server to the local machine. 
        Setting `ThreadNumber` will enable concurrent downloading. 
        Note that setting `ThreadNumber=1` is not equivalent to not setting it. 
        Support download subfolders recursively. 
        Compression can be enabled with `setCompressionLevel(CompressionLevel)`.
        - `[task_]clearDir(Dir, [ThreadNumber])`: 
        Clear the contents of the directory `Dir` on the remote server, but do not delete the directory itself. 
        Setting `ThreadNumber` will enable concurrent clearing. 
        Note that setting `ThreadNumber=1` is not equivalent to not setting it. 
        Support clear subfolders recursively. 
        - `[task_]rmdir(Dir)`: 
        Remove the directory `Dir` from the remote server. 
        Support remove subfolders recursively. 
        - `[task_]mkdir(Dir)`: 
        Create a directory `Dir` on the remote server. 
        Supports create nested folders. 
        Return true if the directory already exists or the creation is successful, and false if it fails.
        - `isDir(Dir)`: 
        Check if `Dir` is a directory on the remote server.
        - `[task_]putFile(FilePath)`: 
        Upload the file located at `FilePath` to the remote server.
        - `[task_]getFile(FilePath)`: 
        Download the file located at `FilePath` from the remote server.
        - `isFile(Path)`: 
        Check if `Path` is a file on the remote server.
        - `[task_]putWorkingDir(ThreadNumber=4)`: 
        Upload the entire working directory to the remote server, EXCLUDING files and folders starting with '.' or '\_'.
        Note that this operation is not allowed if the local working directory is the default user path of the system.
        - `[task_]getWorkingDir(ThreadNumber=4)`: 
        Download the entire working directory from the remote server to the local machine, EXCLUDING files and folders starting with '.' or '\_'. 
        Note that this operation is not allowed if the remote server's working directory is the default path when ssh login.
        - `[task_]clearWorkingDir(ThreadNumber=4)`: 
        Remove the entire remote server's working directory, INCLUDING files and folders starting with '.' or '\_', equivalent to `rmdir(".")`. 
        Note that this operation is not allowed if the remote server's working directory is the default path when ssh login.
    - **`pool(ThreadNumber)`**: 
    Get a thread pool that can execute commands in parallel. 
    `ThreadNumber` limits the number of tasks that can be executed parallelly. 
    This pool provides the same interface as `SystemThreadPool` in 
    [Matlab-JavaThreadPool](https://github.com/CHanzyLazer/CSRC-AlloyDatabase), 
    so it is compatible with `waitPools.m`.
        - `submitSystem(Command)`: 
        Submit command to the thread pool. 
        - `waitUntilDone()`: 
        Suspend the program until all tasks in the thread pool are completed. 
        - `getTaskNumber()`: 
        Get the number of remaining tasks in the thread pool, 
        which is the sum of the number of tasks being executed and those in the queue. 
        - `shutdown()`: 
        Shut down the internal thread pool and wait for all tasks to complete. 
        - `shutdownNow()`: 
        Immediately shut down the internal thread pool and do not wait for tasks to complete.
- **`ServerSLURM`**: 
A terminal specifically designed for servers that support SLURM, 
which also provides the same interface as `SystemThreadPool` in 
[Matlab-JavaThreadPool](https://github.com/CHanzyLazer/CSRC-AlloyDatabase), 
so it is compatible with `waitPools.m`.
    - **Saving and Loading**
        - `save(FilePath)`: 
        Save the entire slurm instance to `FilePath` in json format, including running jobs and queued jobs. 
        To avoid redundant submit, slurm will be paused after saved. 
        **Note that the save file is not encrypted. If there is a password, it will be saved in plain text.**
        **You need to keep the file safe. If you are concerned, you can use public key authentication.**
        **It is NOT RECOMMAND to use this method, because load a single file multi times may result in redundant submit and there is no internal checking.**
        **Use `setMirror` to save instead.**
        - `load(FilePath)`: 
        Static method, load the slurm instance from `FilePath`.
        - `setMirror(Path)`: 
        Set the local mirror of this instance. Any changes of this instance will be synchronized to the local mirror.
        You can reload the mirror by using `load` to continue the jobs.
    - **Getting Instance**
        - `get([SqueueName=username], MaxJobNumber, [MaxThisJobNumber=MaxJobNumber], LocalWorkingDir, RemoteWorkingDir, Username, Hostname, [Port=22], [Password])`: 
        Static method, get the slurm terminal instance and connect to it. 
        Set `MaxJobNumber` to limit the number of jobs running simultaneously on SLURM.
        Set `MaxThisJobNumber` to limit the number of jobs running simultaneously on this instance.
        Set `SqueueName` to set the username in squeue (some SLURM server may have different usernames for SLURM and SSH login).
        - `getKey([SqueueName=username], MaxJobNumber, [MaxThisJobNumber=MaxJobNumber], LocalWorkingDir, RemoteWorkingDir, Username, Hostname, [Port=22], KeyPath)`: 
        Static method, get the slurm terminal instance and connect to it. 
        Authenticate using the key at `KeyPath`. 
    - **Parameter Settings**
        - `setSleepTime(SleepTime)`: 
        Set the wait time in milliseconds for each round of submitting jobs or checking the status of jobs. 500 in default.
        - `setTolerant(Tolerant)`: 
        Set the number of attempts to submit a job if the submission fails. 3 in default.
        Note that network connection issues are not included in this count (i.e., network connection failures will keep retrying to connect). 
        The job submission will only be cancelled if the same failure occurs more than `Tolerant` times, so it is possible to get stuck.
        - `setMirror(Path)`: 
        Set the local mirror of this instance. Any changes of this instance will be synchronized to the local mirror.
        You can reload the mirror by using `load` to continue the jobs.
    - **Basic methods**
        - `ssh()`: 
        Returns an internal `ServerSSH` instance, through which general ssh operations can be performed.
        - `shutdown()`: 
        Disconnects and closes the ssh terminal. No further operations are allowed.
        Will wait for all submitted jobs to complete before doing so.
        - `shutdownNow()`: 
        Immediately disconnects and closes the ssh terminal. No further operations are allowed.
        Will forcefully cancels any running jobs WITHOUT waiting for them to complete.
        - `pause()`: 
        Pause job submit and complete detection. 
        If job is submitting, the program will be suspended until it complete, 
        ensuring that the job queue does not change afterwards.
        - `unpause()`: 
        Resumes job submit and complete detection.
        - `kill(Warning=true)`: 
        Kill the slurm instance directly, similar to killing it through the system level (but safer and more controllable), 
        while retaining the internal running and queued jobs. 
        Print warning when not `setMirror`. Set `Warning` to disable this warning.
    - **Task submission**
        - `submitSystem([BeforeSystem], [AfterSystem], Command, [Partition], NodeNumber=1, OutputPath='.temp/slurm/out-%j')`: 
        Submits a command to the SLURM server, which is equivalent to writing a bash script and submitting it by using `sbatch`.
        Use the `Task` object to specify `BeforeSystem` and `AfterSystem`, which will be executed before the task starts and after it completes, respectively.
        - `submitBash([BeforeSystem], [AfterSystem], BashPath, [Partition], NodeNumber=1, OutputPath='.temp/slurm/out-%j')`: 
        Submits a bash script directly to the SLURM server, 
        which is equivalent to uploading the local script at `BashPath` to the remote server and submitting it by using `sbatch`. 
        - `submitSrun([BeforeSystem], [AfterSystem], Command, [Partition], TaskNumber=1, MaxTaskNumberPerNode=20, OutputPath='.temp/slurm/out-%j')`: 
        Submits an `srun` command directly to the SLURM server. 
        This is equivalent to appending `srun` to the command, writing it into a bash script, 
        and then submitting it by using `sbatch`. 
        The number of nodes is automatically calculated based on the input.
        - `submitSrunBash([BeforeSystem], [AfterSystem], BashPath, [Partition], TaskNumber=1, MaxTaskNumberPerNode=20, OutputPath='.temp/slurm/out-%j')`: 
        ubmits a bash script to be executed using `srun` directly to the SLURM server. 
        First, the script located at `BashPath` on the local machine is uploaded to the remote server. 
        Then, the script is executed using `srun`, written into a bash script, and submitting it by using `sbatch`. 
        The number of nodes required is automatically calculated based on the input.
    - **Practical Methods**
        - `jobNumber()`: 
        Gets the number of jobs running on the SLURM server of this user.
        - `jobIDs()`: 
        Gets the job IDs of the jobs running on the SLURM server of this user.
        - `cancelAll()`: 
        Cancels all jobs running on the SLURM server of this user, even if they were not submitted by object. 
        It also clears queued jobs in this object (if any).
        - `cancelThis()`: 
        Cancels all jobs submitted by this object and clears any queued jobs.
        - `undo()`: 
        Attempts to cancel the last submitted job. 
        If it is queued, cancellation is successful and the corresponding command is returned. 
        If it has already been submitted, cancellation fails and `null` is returned.
        - `getActiveCount()`: 
        Gets the number of jobs currently being executed (submitted by this object only).
        - `getQueueSize()`: 
        Gets the number of jobs currently queued.
        - `waitUntilDone()`: 
        Suspends the program until all jobs are completed.
        - `getTaskNumber()`: 
        Gets the number of remaining jobs, i.e., `getActiveCount() + getQueueSize()`.
        - `awaitTermination()`: 
        Suspends the program until the internal thread pool is closed (used in conjunction with `shutdown()`).
        - `getActiveJobIDs()`: 
        Gets the job IDs of the currently executing jobs (submitted by this object only).
        - `getQueueCommands()`: 
        Gets the list of commands currently queued.
    - **`code.UT`**: 
    Utility class
        - `Pair`: 
        A class similar to the `Pair` class in the C++ STL, implemented in Java.
        - `Task`: 
        A class that encapsulates the operation in `ServerSSH` or `ServerSLURM` by overriding the `run` method. 
        It will return `false` or throw an exception when the execution has failed.
        - `mergeTask(Task1, Task2)`: 
        Static method, merges two `Task` objects into one. 
        `Task1` is executed first. Any execution failure will interrupt the subsequent execution.
        - `tryTask(Task, [Tolerant])`: 
        Static method, attempts to execute `Task`. 
        If it fails, it returns `false` instead of throwing an exception. 
        Set `Tolerant` to retry the execution `Tolerant` times after fail.


# Code
This project uses [Gradle](https://gradle.org/) for management, which can be installed or omitted.

In the `include/java` directory, run `./gradlew build` to compile. By default, the jar file will be output to the parent directory.

A JDK is required, jdk11 or jdk8 is recommended.

# License
This code is licensed under the [MIT License](LICENSE).  
