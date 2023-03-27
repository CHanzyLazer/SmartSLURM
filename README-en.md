**切换到 [中文](README.md)**

# SmartSLURM
A SLURM controler make you use SLURM like a local application

This program consists of two parts: 
[ServerSSH](include/java/src/ServerSSH.java) contains basic methods for managing SSH servers, and 
[ServerSLURM](include/java/src/ServerSLURM.java) is specifically adapted for servers that support the SLURM system.

This program is written in Java and uses [jsch](http://www.jcraft.com/jsch/) to connect to SSH servers. 
It also supplements common functions and optimizes file transmission.

This program does not require any third-party libraries so it can be used directly. 
[demo.m](demo.m) provides a basic example for use in Matlab, while 
[demo.ipynb](demo.ipynb) provides a basic example for use in Python.

# Usage
Download from [Release](https://github.com/CHanzyLazer/SmartSLURM/releases/latest). 
`SmartSLURM-with-demo.zip` contains runnable examples, and `smartSLURM.jar` is the jar package only.

In Matlab, you need to import the java classes first. 
In this example, the jar package is located in the include directory:
```matlab
javaaddpath('include/smartSLURM.jar');
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
ServerSSH = GATEWAY.jvm.ServerSSH
ServerSLURM = GATEWAY.jvm.ServerSLURM
```
Finally, simply close the GATEWAY:
```python
GATEWAY.shutdown()
```

# Interface Description
- `ServerSSH`: Provides basic methods for managing SSH servers, 
and all operations will attempt to reconnect once when connection is interrupted.
    - `get(LocalWorkingDir, RemoteWorkingDir, Username, Hostname, [Port=22], [Password])`: Static method, 
    gets an SSH terminal and connects to it. If no password is provided, key authentication will be used, 
    and will use the key at `{user.home}/.ssh/id_rsa` in default.
    - `getKey(LocalWorkingDir, RemoteWorkingDir, Username, Hostname, Port, KeyPath)`: Static method, 
    gets an SSH terminal and connects to it, using the key located at `KeyPath` for authentication. 
    **Note that JSch only supports classic format openSSH key, so flag `-m pem` must be added when generating keys.**
    - `setCompressionLevel(CompressionLevel)`: Sets the compression level (1-9) when transferring over SSH. 
    A value less or equal to 0 will disable compression. Compression is not used by default.
    - `isConnecting()`: Checks whether the SSH connection is being maintained.
    - `connect()`: If is disconnected, it will be reconnected. 
    This method does not need to be called manually because most methods will attempt to reconnect once.
    - `shutdown()`: Disconnects and closes the SSH terminal. No further operations are allowed.
    - `system(Command)`: Executes a command on the SSH terminal, similar to the `system` function in Matlab.
    - `putDir(Dir, [ThreadNumber])`: Upload directory `Dir` to remote server. 
    Setting `ThreadNumber` will enable concurrent uploading. 
    Note that setting `ThreadNumber=1` is not equivalent to not setting it. 
    Supports uploading subfolders recursively. 
    For large files, compression can be enabled to speed up the process with `setCompressionLevel(CompressionLevel)`.
    - `getDir(Dir, [ThreadNumber])`: Download directory `Dir` from remote server to local machine. 
    Setting `ThreadNumber` will enable concurrent downloading. 
    Note that setting `ThreadNumber=1` is not equivalent to not setting it. 
    Supports downloading subfolders recursively. 
    For large files, compression can be enabled to speed up the process with `setCompressionLevel(CompressionLevel)`.
    - `clearDir(Dir)`: Clear directory `Dir` on remote server without deleting the folder. 
    Supports clearing subfolders recursively.
    - `rmdir(Dir)`: Remove directory `Dir` from remote server. 
    Supports deleting subfolders recursively.
    - `mkdir(Dir)`: Create directory `Dir` on remote server. 
    Supports creating nested folders. 
    Returns `true` if folder already exists or was successfully created, `false` otherwise.
    - `putWorkingDir(ThreadNumber=4)`: Upload entire working directory to remote server, 
    ignoring files and folders starting with '.' or '_'.
    - `getWorkingDir(ThreadNumber=4)`: Download entire working directory from remote server, 
    ignoring files and folders starting with '.' or '_'.
    - `clearWorkingDir(ThreadNumber=4)`: Remove entire working directory on remote server, 
    INCLUDING files and folders starting with '.' or '_', equivalent to `rmdir(".")`.
    - `pool(ThreadNumber)`: Gets a thread pool that can execute commands in parallel. 
    `ThreadNumber` limits the number of tasks that can be executed parallelly. 
    This pool provides the same interface as `SystemThreadPool` in 
    [Matlab-JavaThreadPool](https://github.com/CHanzyLazer/CSRC-AlloyDatabase), making it compatible with `waitPools.m`.
        - `submitSystem(Command)`: Submit command to the thread pool. 
        - `waitUntilDone()`: Suspend the program until all tasks in the thread pool are completed. 
        - `getTaskNumber()`: Get the number of remaining tasks in the thread pool, 
        which is the sum of the number of tasks being executed and those in the queue. 
        - `shutdown()`: Shut down the internal thread pool and wait for all tasks to complete. 
        - `shutdownNow()`: Immediately shut down the internal thread pool and do not wait for tasks to complete.
- `ServerSLURM`: A terminal specifically designed for servers that support SLURM, 
which also provides the same interface as `SystemThreadPool` in 
[Matlab-JavaThreadPool](https://github.com/CHanzyLazer/CSRC-AlloyDatabase).
    - `get(ThreadNumber, LocalWorkingDir, RemoteWorkingDir, Username, Hostname, [Port=22], [Password])`: Static method, 
    gets a SLURM terminal and connect to it. `ThreadNumber` limiting the number of jobs that can run simultaneously on SLURM.
    - `getKey(ThreadNumber, LocalWorkingDir, RemoteWorkingDir, Username, Hostname, Port, KeyPath)`: Static method, 
    gets a SLURM terminal and connect to it, using the key at `KeyPath` for authentication.
    - `ssh()`: Return the internal `ServerSSH` instance and implement general ssh operations through it.
    - `shutdown()`: Disconnects and closes this SLURM terminal, disallowing further operations. 
    Waits for all submitted jobs complete before shutting down.
    - `shutdownNow()`: Immediately disconnects and closes the SLURM terminal, disallowing further operations. 
    It does not wait for jobs complete and forcibly cancels any jobs that are currently running.
    - `submitSystem(Command, [Partition='work'], NodeNumber=1, OutputPath='.temp/slurm/out-%j')`: 
    Submits a command to the SLURM server, which is equivalent to writing a bash script and submitting it by using `sbatch`.
    - `submitSrun(Command, [Partition='work'], TaskNumber=1, MaxTaskNumberPerNode=20, OutputPath='.temp/slurm/out-%j')`: 
    Submits an `srun` command directly to the SLURM server. 
    This is equivalent to appending `srun` to the command, writing it into a bash script, 
    and then submitting it using `sbatch`. 
    The number of nodes is automatically calculated based on the input.
    - `submitSrunBash(BashPath, [Partition='work'], TaskNumber=1, MaxTaskNumberPerNode=20, OutputPath='.temp/slurm/out-%j')`: 
    Submits a bash script to be executed using `srun` directly to the SLURM server. 
    First, the script located at `BashPath` on the local machine is uploaded to the remote server. 
    Then, the script is executed using `srun`, written into a bash script, and submitted using `sbatch`. 
    The number of nodes required is automatically calculated based on the input.
    - `jobNumber()`: Gets the number of jobs running on the SLURM server of this user.
    - `jobIDs()`: Gets the job IDs of the jobs running on the SLURM server of this user.
    - `cancelAll()`: Cancels all jobs running on the SLURM server of this user, even if they were not submitted by object. 
    It also clears queued jobs in this object (if any).
    - `cancelThis()`: Cancels all jobs submitted by this object and clears any queued jobs.
    - `undo()`: Attempts to cancel the last submitted job. 
    If it is queued, cancellation is successful and the corresponding command is returned. 
    If it has already been submitted, cancellation fails and `null` is returned.
    - `getActiveCount()`: Gets the number of jobs currently being executed (submitted by this object only).
    - `getQueueSize()`: Gets the number of jobs currently queued.
    - `waitUntilDone()`: Suspends the program until all jobs are completed.
    - `getTaskNumber()`: Gets the number of remaining jobs, i.e., `getActiveCount() + getQueueSize()`.
    - `awaitTermination()`: Suspends the program until the internal thread pool is closed 
    (used in conjunction with `shutdown()`).
    - `getActiveJobIDs()`: Gets the job IDs of the currently executing jobs (submitted by this object only).
    - `getQueueCommands()`: Gets the list of commands currently queued.

# Code
This project uses [Gradle](https://gradle.org/) for management, which can be installed or omitted.

In the `include/java` directory, run `./gradlew build` to compile. By default, the jar file will be output to the parent directory.

A JDK is required, jdk11 or jdk8 is recommended.

# License
This code is licensed under the [MIT License](LICENSE).  
