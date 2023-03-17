**Switch to [English](README-en.md)**

# SmartSLURM
能使你像使用本地应用一样方便的管理 SLURM 服务器

本程序包含两个部分，[ServerSSH](include/java/src/ServerSSH.java) 包含基本的管理 ssh 服务器的方法，
[ServerSLURM](include/java/src/ServerSLURM.java) 则专门对支持 SLURM 系统的服务器进行了适配

本程序使用 java 编写，使用 [jsch](http://www.jcraft.com/jsch/) 来实现连接 ssh 服务器以及基本的功能，
并在此基础上补充了常用的功能，并对文件传输部分进行了专门的优化

本程序不需要任何第三方库，可以直接使用，
[demo.m](demo.m) 作为 matlab 使用的基本例子，[demo.ipynb](demo.ipynb) 作为在 python 中使用的基本例子

# 使用方法
从 [Release](https://github.com/CHanzyLazer/SmartSLURM/releases/tag/v1.2) 中下载， 
`SmartSLURM-with-demo.zip` 包含了可以运行的使用例子，`smartSLURM.jar` 则只包含 jar 包

在 matlab 中，首先需要导入 java 类，在使用例子中 jar 包位于 include 目录下：
```matlab
javaaddpath('include/smartSLURM.jar');
```
最后移除目录：
```matlab
clear;
javarmpath('include/smartSLURM.jar');
```

在 python 中，需要使用第三方库来使用调用 java 代码，这里使用 [py4j](https://www.py4j.org/)：
```python
from py4j.java_gateway import JavaGateway
GATEWAY = JavaGateway.launch_gateway(classpath='include/smartSLURM.jar')
ServerSSH = GATEWAY.jvm.ServerSSH
ServerSLURM = GATEWAY.jvm.ServerSLURM
```
最后直接关闭 GATEWAY：
```python
GATEWAY.shutdown()
```

# 接口说明
- `ServerSSH`: 提供基本的管理 ssh 服务器的方法，所有操作都会在连接中断后尝试一次重新连接
    - `get(LocalWorkingDir, RemoteWorkingDir, Username, Hostname, [Port=22], [Password])`：静态方法，获取 ssh 终端并且连接到终端，
    在不提供密码时会使用密钥登录，默认会使用 `{user.home}/.ssh/id_rsa` 位置的密钥
    - `getKey(LocalWorkingDir, RemoteWorkingDir, Username, Hostname, Port, KeyPath)`：静态方法，获取 ssh 终端并且连接到终端，
    使用 `KeyPath` 位置的密钥进行认证，
    **注意 jsch 只支持经典格式的 openSSH 密钥，因此在生成密钥时需要加上 `-m pem` 参数**
    - `setCompressionLevel(CompressionLevel)`: 设置 ssh 传输时的压缩等级（1-9），设置小于等于 0 的值会关闭压缩，默认不进行压缩
    - `isConnecting()`: 检测 ssh 是否正保持着连接
    - `connect()`: 如果连接被断开则会重新连接，不需要手动调用因为基本所有方法都会尝试一次重新连接
    - `shutdown()`: 断开连接并且关闭此 ssh 终端，不再允许其他操作
    - `system(Command)`: 在 ssh 终端上执行指令，类似 matlab 中的 system 函数
    - `putDir(Dir, [ThreadNumber])`: 上传目录 `Dir` 到远程服务器，设置 `ThreadNumber` 则会开 启并发上传，注意设置 `ThreadNumber=1` 与不设置并不等价。
    支持递归子文件夹进行上传，对于大文件可以通过 `setCompressionLevel(CompressionLevel)` 来开 启压缩来加速
    - `getDir(Dir, [ThreadNumber])`: 下载远程服务器的目录 `Dir` 到本地，设置 `ThreadNumber` 则会开启并发下载，注意设置 `ThreadNumber=1` 与不设置并不等价。
    支持递归子文件夹进行下载，对于大文件可以通过 `setCompressionLevel(CompressionLevel)` 来开 启压缩来加速
    - `clearDir(Dir)`: 清空远程服务器的目录 `Dir`，但是不删除文件夹，支持递归子文件夹进行清空
    - `rmdir(Dir)`: 移除远程服务器的目录 `Dir`，支持递归子文件夹进行删除
    - `mkdir(Dir)`: 在创建远程服务器上创建目录 `Dir`，支持跨文件夹创建文件夹，如果已经存在文件夹或者创建文件夹成功则返回 true，创建文件夹失败返回 false
    - `putWorkingDir(ThreadNumber=4)`: 上传整个工作目录到远程服务器，忽略 '.'，'_' 开头的 文件和文件夹
    - `getWorkingDir(ThreadNumber=4)`: 从远程服务器下载整个工作目录到本地，忽略 '.'，'_' 开头的文件和文件夹
    - `clearWorkingDir(ThreadNumber=4)`: 移除整个远程服务器的工作区，注意不忽略 '.'，'_' 开头的文件和文件夹，等价于 `rmdir(".")`
    - `pool(ThreadNumber)`: 获取一个可以并行执行指令的线程池，`ThreadNumber` 限制同时执行的数目。
    此 pool 提供和 [Matlab-JavaThreadPool](https://github.com/CHanzyLazer/CSRC-AlloyDatabase) 中 `SystemThreadPool` 完全相同的接口，从而兼容其中的 `waitPools.m`
        - `submitSystem(Command)`: 向线程池中提交指令
        - `waitUntilDone()`: 挂起程序直到线程池中的任务全部完成
        - `getTaskNumber()`: 获取线程池中剩余任务数目，即正在执行的任务以及正在排队的任务的总和
        - `shutdown()`: 关闭内部的线程池，会等待所有任务完成
        - `shutdownNow()`: 立刻关闭内部的线程池，不会等待任务完成
- `ServerSLURM`: 专门对支持 SLURM 系统的服务器进行适配的终端，
本身也提供了和 [Matlab-JavaThreadPool](https://github.com/CHanzyLazer/CSRC-AlloyDatabase) 中 `SystemThreadPool` 完全相同的接口
    - `get(ThreadNumber, LocalWorkingDir, RemoteWorkingDir, Username, Hostname, [Port=22], [Password])`：静态方法，获取 slurm 终端并且连接到终端，
    指定 `ThreadNumber` 来限制在 SLURM 上同时运行的作业数目
    - `getKey(ThreadNumber, LocalWorkingDir, RemoteWorkingDir, Username, Hostname, Port, KeyPath)`：静态方法，获取 slurm 终端并且连接到终端，
    使用 `KeyPath` 位置的密钥进行认证
    - `ssh()`: 返回内部的 `ServerSSH` 实例，通过此实现一般的 ssh 操作
    - `shutdown()`: 断开连接并且关闭此 ssh 终端，不再允许其他操作，在此之前会等待提交的任全部完成
    - `shutdownNow()`: 立刻断开连接并且关闭此 ssh 终端，不再允许其他操作，不会等待任务完成且会强行取消掉正在执行的任务
    - `submitSystem(Command, [Partition='work'], NodeNumber=1, OutputPath='.temp/slurm/out-%j')`: 
    向 SLURM 服务器提交指令，相当于此指令写入了一个 bash 脚本并且使用 sbatch 来提交
    - `submitSrun(Command, [Partition='work'], TaskNumber=1, MaxTaskNumberPerNode=20, OutputPath='.temp/slurm/out-%j')`: 
    向 SLURM 服务器直接提交 srun 指令，相当于在指令前增加一个 srun 并且写入 bash 脚本然后用sbatch 来提交，会根据输入自动计算需要的节点数目
    - `submitSrunBash(BashPath, [Partition='work'], TaskNumber=1, MaxTaskNumberPerNode=20, OutputPath='.temp/slurm/out-%j')`: 
    向 SLURM 服务器直接提交 srun 运行的脚本，首先会将本地的处于 `BashPath` 的脚本上传到远 服务器，然后使用 srun 实行这个脚本，
    并且将此操作写入 bash 脚本然后使用 sbatch 来提交，会根据输入自动计算需要的节点数目
    - `jobNumber()`: 获取此用户在 SLURM 服务器上正在执行的任务数目
    - `jobIDs()`: 获取此用户在 SLURM 服务器上正在执行的任务的编号
    - `cancelAll()`: 取消此用户在 SLURM 服务器上正在执行的所有任务，即使这个任务不是通过这对象提交的。同时会清空此对象中排队的任务（如果有的话）
    - `cancelThis()`: 取消这个对象提交的所有任务并且清空排队的任务
    - `undo()`: 尝试取消最后一次提交的任务，如果在排队则取消成功返回对应的指令，如果已经提则取消失败，返回 null
    - `getActiveCount()`: 获取正在执行的任务数目（仅限本对象提交的）
    - `getQueueSize()`: 获取正在排队的任务数目
    - `waitUntilDone()`: 挂起程序直到任务全部完成
    - `getTaskNumber()`: 获取剩余任务数目，即 `getActiveCount() + getQueueSize()`
    - `awaitTermination()`: 挂起直到内部线程池关闭（结合 `shutdown()` 使用）
    - `getActiveJobIDs()`: 获取正在执行的任务编号（仅限本对象提交的）
    - `getQueueCommands()`: 获取正在排队的指令列表

# 代码部分
本项目使用 [Gradle](https://gradle.org/) 进行管理，在这里可以不安装 Gradle

在 `include/java` 目录下，运行 `./gradlew build` 即可进行编译，默认会将 jar 文件输出到上级目录中

需要系统拥有 jdk，可以从 [这里](https://mirrors.tuna.tsinghua.edu.cn/Adoptium/) 下载 jdk（建议使用 jdk11 或者 jdk8 ）


# License
This code is licensed under the [MIT License](LICENSE).
