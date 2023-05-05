**Switch to [English](README-en.md)**

# SmartSLURM
能使你像使用本地应用一样方便的管理 SLURM 服务器，可以轻松处理类似于这样的工作流程：

$$
\begin{array}{c}
\text{批量生成输入文件}\longrightarrow 
\text{上传输入文件到服务器}\longrightarrow 
\text{提交任务}\longrightarrow \\
\text{等待任务执行完成}\longrightarrow 
\text{从服务器下载输出文件}\longrightarrow \\
\text{对输出文件数据处理}\longrightarrow 
\text{根据结果生成下一批输入文件}\longrightarrow \cdots
\end{array}
$$

本程序包含两个部分，[ServerSSH](include/java/src/com/chanzy/ServerSSH.java) 包含基本的管理 ssh 服务器的方法，
[ServerSLURM](include/java/src/com/chanzy/ServerSLURM.java) 则专门对支持 SLURM 系统的服务器进行了适配

本程序使用 java 编写，使用 [jsch](http://www.jcraft.com/jsch/) 来实现连接 ssh 服务器以及基本的功能，
并在此基础上补充了常用的功能，并对文件传输部分进行了专门的优化

本程序不需要任何第三方库，可以直接使用，
[demo.m](demo.m) 作为 matlab 使用的基本例子，[demo.ipynb](demo.ipynb) 作为在 python 中使用的基本例子

# 使用方法
从 [Release](https://github.com/CHanzyLazer/SmartSLURM/releases/latest) 中下载， 
`SmartSLURM-with-demo.zip` 包含了可以运行的使用例子，`smartSLURM.jar` 则只包含 jar 包

在 matlab 中，首先需要导入 java 类，在使用例子中 jar 包位于 include 目录下：
```matlab
javaaddpath('include/smartSLURM.jar');
```
软件包为 `com.chanzy.*`，如果希望使用方便可以先进行 import：
```matlab
import com.chanzy.*
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
ServerSSH = GATEWAY.jvm.com.chanzy.ServerSSH
ServerSLURM = GATEWAY.jvm.com.chanzy.ServerSLURM
```
最后直接关闭 GATEWAY：
```python
GATEWAY.shutdown()
```
下面统一用 matlab 代码来演示

## 快速开始
对于上面提到的工作流程，则可以使用这样的脚本实现：
```matlab
% 导入 jar 包
javaaddpath('include/smartSLURM.jar');
% 导入软件包
import com.chanzy.*
% 获取 slurm 端
slurm = ServerSLURM.get(8, 'path/to/project/in/local', 'path/to/project/in/remote', 'username', 'hostname', 'password');
% 上传输入文件到远程服务器，指定 4 线程并发上传（可选）
slurm.ssh().putDir('path/to/in/file/dir', 4);
% 提交 njobs 个 srun 任务，每个任务使用 40 个核计算，每个节点最多 20 核（没设置分区则会在默认分区执行）
for i = 1:njobs
    slurm.submitSrun(['software/in/remote < path/to/in/file-' num2str(i)], 40, 20);
end
% 等待任务执行完成
slurm.waitUntilDone();
% 也支持 Matlab-JavaThreadPool 中的 waitPools 函数
% waitPools(slurm);
% 也支持 java 线程池常用的方法，由于还有后续操作所以不能 shutdown
% slurm.shutdown();
% slurm.awaitTermination();
% 从服务器下载输出文件，如果输出文件较大则可以设置压缩等级来加速传输
slurm.ssh().setCompressionLevel(4);
slurm.ssh().getDir('path/to/out/file/dir', 4);
slurm.ssh().setCompressionLevel(-1); % 传输完成后取消数据压缩
% 数据处理
% ...
% 提交下一批输入文件，在此之前一般需要先清空旧的输入文件防止相互干扰
slurm.ssh().clearDir('path/to/in/file/dir');
slurm.ssh().putDir('path/to/in/file/dir', 4);
% ...
```

## 进阶用法
可能还会存在以下需求：
- 超长时间的任务，需要 slurm 端支持保存和读取的功能
- 上传输入文件以及下载输入文件可能失败，需要重复尝试
- 上传和下载耗时过久，不希望阻塞主线程

对于超长时间的任务，smartSLURM 支持 `setMirror` 的功能，可以设置本地的镜像，后续需要可以重新读取镜像继续任务：
```matlab
% 设置本地镜像
slurm.setMirror('path/to/local/mirror');
% 同样提交任务
for i = 1:njobs
    slurm.submitSrun(['software/in/remote < path/to/in/file-' num2str(i)], 40, 20);
end
% 等待一段时间等待任务提交完成
pause(60);
% 也可通过 getQueueSize 来检测排队的任务数量来等待
% while slurm.getQueueSize() > 0
%     pause(10);
% end
% 然后可以直接 kill 掉这个 slurm 去做其他任务
slurm.kill();
% ...
% 等待需要检测时重新加载镜像来查看任务完成情况
slurm = ServerSLURM.load('path/to/local/mirror');
if slurm.getTaskNumber() > 0
    disp('Jobs not finish yet.');
else
    disp('Jobs finished.');
    % 执行下载输出文件等操作
    slurm.ssh().getDir('path/to/out/file/dir', 4);
    % ...
end
```
需要注意的几点：
- 不一定需要等待排队的任务都提交了才能 kill 掉 slurm，镜像文件同样会记录排队的任务，
只是 kill 后正在执行的任务完成后不会继续执行排队的任务，重新 load 镜像文件后会自动执行
- 一定需要 kill 掉旧的 slurm 再去 load 新的 slurm，否则可能会出现重复提交的问题，程序会对其进行检测并且报出警告来提示
- 由于提交任务是在另一个线程后台执行的，因此如果 `submitSrun` 后马上 kill 掉 slurm，这些任务都还会停留在排队阶段

对于上传和下载文件的问题，smartSLURM 支持将基本操作打包成 `Task` 对象的功能，并且附加到提交任务上一起执行：
```matlab
% 获取执行任务之前需要上传输入文件的 task
taskBefore = slurm.ssh().task_putDir('path/to/in/file/dir', 4);
% 获取任务完成后需要下载输出文件的 task
taskAfter = slurm.ssh().task_getDir('path/to/out/file/dir', 4);
% 提交任务，将这两个 task 附加上去
for i = 1:njobs
    slurm.submitSrun(taskBefore, taskAfter, ['software/in/remote < path/to/in/file-' num2str(i)], 40, 20);
end
% 等待任务完成...
% ...
```
这样 slurm 端会自动在执行 srun 命令之前，运行 `taskBefore`，而在运行完成 srun 之后去执行 `taskAfter`，
并且 slurm 内部在执行这些时自带了失败重试的功能（默认会重试 3 次，可以通过 `setTolerant` 来修改）

需要注意的几点：
- 可以只提交 `taskBefore` 而不提交 `taskAfter`：
```matlab
slurm.submitSrun(taskBefore, 'software/in/remote < path/to/in/file', 40, 20);
```
- 多个 task 可以通过 `mergeTask` 来合并：
```matlab
task1 = slurm.ssh().task_system('python dataPreprocessing.py path/to/out/file/dir');
task2 = slurm.ssh().task_getDir('path/to/out/file/dir', 4);
taskAfter = code.UT.mergeTask(task1, task2); % 包括了在远程服务器使用 python 脚本预处理数据和下载处理后的数据
```
- 可以直接在脚本中运行 task：
```matlab
task = slurm.ssh().task_putDir('path/to/out/file/dir', 4);
task.run(); % 相当于 slurm.ssh().putDir('path/to/out/file/dir', 4);
% 也可带有尝试次数的运行
suc = code.UT.tryTask(task, 3);
% 相当于
% suc = false;
% for i = 1:3
%     suc = code.UT.tryTask(task);
%     if suc; break; end
% end
```
- 不需要在 task 的指令中出现 `{`，`}`，`:`，`null` 这几个符号，会影响反序列化


# 接口说明
- **`ServerSSH`**：
提供基本的管理 ssh 服务器的方法，所有操作都会在连接中断后尝试一次重新连接
    - **保存和加载**
        - `save(FilePath)`：
        保存这个 ssh 端到 `FilePath`，保存为 json 格式，
        **注意这个保存是没有加密，如果有密码则会保存明文的密码，需要保管好文件，如果在意可以使用密钥连接**
        - `load(FilePath)`：
        静态方法，从 `FilePath` 加载 ssh 端
    - **获取对象**
        - `get([LocalWorkingDir=""], [RemoteWorkingDir=""], Username, Hostname, [Port=22], [Password])`：
        静态方法，获取 ssh 终端并且连接到终端，在不提供密码时会使用密钥登录，默认会使用 `{user.home}/.ssh/id_rsa` 位置的密钥
        - `getKey([LocalWorkingDir=""], [RemoteWorkingDir=""], Username, Hostname, [Port=22], KeyPath)`：
        静态方法，获取 ssh 终端并且连接到终端，使用 `KeyPath` 位置的密钥进行认证，
        **注意 jsch 只支持经典格式的 openSSH 密钥，因此在生成密钥时需要加上 `-m pem` 参数**
        - `getPassword([LocalWorkingDir=""], [RemoteWorkingDir=""], Username, Hostname, [Port=22], Password)`：
        静态方法，获取 ssh 终端并且连接到终端，使用密码认证，认为最后一个输入的字符串是密码
    - **参数设置**
        - `setLocalWorkingDir(LocalWorkingDir)`：
        设置本地的工作目录，输入 null 或者空字符串则会设置为系统的用户路径 `System.getProperty("user.home")`
        - `setRemoteWorkingDir(RemoteWorkingDir)`：
        设置远程服务器的工作目录，输入 null 或者空字符串则会设置为连接 ssh 时所在的默认路径
        - `setCompressionLevel(CompressionLevel)`：
        设置 ssh 传输时的压缩等级（1-9），设置小于等于 0 的值会关闭压缩，默认不进行压缩
        - `setBeforeSystem(Command)`：
        设置在执行 `system` 指令之前永远会附加的指令，例如环境变量的设置等等
        - `setPassword(Password)`：
        修改登录的密码，同时会将认证模式修改为密码认证
        - `setKey(KeyPath)`：
        修改密钥路径，同时会将认证模式修改为密钥认证
    - **基本方法**
        - `isConnecting()`：
        检测 ssh 是否正保持着连接
        - `connect()`：
        如果连接被断开则会重新连接，一般不需要手动调用因为基本所有方法都会尝试一次重新连接
        - `disconnect()`：
        手动断开 ssh 的连接，一般不需要手动调用因为基本所有方法都会尝试一次重新连接
        - `shutdown()`：
        断开连接并且关闭此 ssh 终端，不再允许其他操作
        - `session()`：
        返回当前 ssh 的 session，一般不需要使用因为大部分常用功能已经经过了包装
    - **实用方法**
        - `[task_]system(Command)`：
        在 ssh 终端上执行指令，类似 matlab 中的 system 函数。可以附加 `task_` 来获得此方法的 Task 对象（下同）
        - `[task_]putDir(Dir, [ThreadNumber])`：
        上传目录 `Dir` 到远程服务器，设置 `ThreadNumber` 则会开启并发上传，注意设置 `ThreadNumber=1` 与不设置并不等价。
        支持递归子文件夹进行上传，对于大文件可以通过 `setCompressionLevel(CompressionLevel)` 来开启压缩来加速
        - `[task_]getDir(Dir, [ThreadNumber])`：
        下载远程服务器的目录 `Dir` 到本地，设置 `ThreadNumber` 则会开启并发下载，注意设置 `ThreadNumber=1` 与不设置并不等价。
        支持递归子文件夹进行下载，对于大文件可以通过 `setCompressionLevel(CompressionLevel)` 来开启压缩来加速
        - `[task_]clearDir(Dir, [ThreadNumber])`：
        清空远程服务器的目录 `Dir`，但是不删除文件夹，设置 `ThreadNumber` 则会开启并发下载，注意设置 `ThreadNumber=1` 与不设置并不等价。
        支持递归子文件夹进行清空
        - `[task_]rmdir(Dir)`：
        移除远程服务器的目录 `Dir`，支持递归子文件夹进行删除
        - `[task_]mkdir(Dir)`：
        在创建远程服务器上创建目录 `Dir`，支持跨文件夹创建文件夹，
        如果已经存在文件夹或者创建文件夹成功则返回 true，创建文件夹失败返回 false
        - `isDir(Dir)`：
        判断输入的 `Dir` 是否在远程服务器上是一个目录
        - `[task_]putFile(FilePath)`：
        上传位于 `FilePath` 的文件到远程服务器
        - `[task_]getFile(FilePath)`：
        下载远程服务器上位于 `FilePath` 的文件到本地
        - `isFile(Path)`：
        判断输入的 `Path` 是否在远程服务器上是一个文件
        - `[task_]putWorkingDir(ThreadNumber=4)`：
        上传整个工作目录到远程服务器，忽略 '.'，'_' 开头的 文件和文件夹，
        注意如果本地工作目录是默认系统的用户路径则不允许此操作
        - `[task_]getWorkingDir(ThreadNumber=4)`：
        从远程服务器下载整个工作目录到本地，忽略 '.'，'_' 开头的文件和文件夹，
        注意如果远程服务器工作目录是 ssh 登录时的默认路径则不允许此操作
        - `[task_]clearWorkingDir(ThreadNumber=4)`：
        移除整个远程服务器的工作区，注意不忽略 '.'，'_' 开头的文件和文件夹，等价于 `rmdir(".")`，
        注意如果远程服务器工作目录是 ssh 登录时的默认路径则不允许此操作
    - **`pool(ThreadNumber)`**：
    获取一个可以并行执行指令的线程池，`ThreadNumber` 限制同时执行的数目。此 pool 提供和 
    [Matlab-JavaThreadPool](https://github.com/CHanzyLazer/CSRC-AlloyDatabase) 
    中 `SystemThreadPool` 完全相同的接口，因此兼容其中的 `waitPools.m`
        - `submitSystem(Command)`：
        向线程池中提交指令
        - `waitUntilDone()`：
        挂起程序直到线程池中的任务全部完成
        - `getTaskNumber()`：
        获取线程池中剩余任务数目，即正在执行的任务以及正在排队的任务的总和
        - `shutdown()`：
        关闭内部的线程池，会等待所有任务完成
        - `shutdownNow()`：
        立刻关闭内部的线程池，不会等待任务完成
- **`ServerSLURM`**：
专门对支持 SLURM 系统的服务器进行适配的终端，本身也提供了和 
[Matlab-JavaThreadPool](https://github.com/CHanzyLazer/CSRC-AlloyDatabase) 
中 `SystemThreadPool` 完全相同的接口，因此兼容其中的 `waitPools.m`
    - **保存和加载**
        - `save(FilePath)`：
        保存整个 slurm 端到 `FilePath`，包括正在执行的任务以及在排队的任务，保存为 json 格式，为了避免重复提交保存后设置 `pause` 暂停任务提交，
        **注意这个保存是没有加密，如果有密码则会保存明文的密码，需要保管好文件，如果在意可以使用密钥连接**。
        **不建议使用，同时 load 单个文件会出现重复提交的问题，并且内部没有进行检测所以难以发现，尽量使用 `setMirror` 保存**
        - `load(FilePath)`：
        静态方法，从 `FilePath` 加载 slurm 端
        - `setMirror(Path)`：
        设置此对象的本地镜像，之后任何改动都会同步到本地的镜像上。可以通过 `load` 来重新加载这个镜像来继续操作
    - **获取对象**
        - `get([SqueueName=username], MaxJobNumber, [MaxThisJobNumber=MaxJobNumber], [LocalWorkingDir=""], [RemoteWorkingDir=""], Username, Hostname, [Port=22], [Password])`：
        静态方法，获取 slurm 终端并且连接到终端，
        指定 `MaxJobNumber` 来限制在 SLURM 上同时运行的作业数目，指定 `MaxThisJobNumber` 来限制本对象同时运行的作业个数，
        指定 `SqueueName` 来设置 squeue 时这个用户的名称（部分 SLURM 上和 ssh 登录的用户名不同）
        - `getKey([SqueueName=username], MaxJobNumber, [MaxThisJobNumber=MaxJobNumber], [LocalWorkingDir=""], [RemoteWorkingDir=""], Username, Hostname, [Port=22], KeyPath)`：
        静态方法，获取 slurm 终端并且连接到终端，使用 `KeyPath` 位置的密钥进行认证
        - `getPassword([SqueueName=username], MaxJobNumber, [MaxThisJobNumber=MaxJobNumber], [LocalWorkingDir=""], [RemoteWorkingDir=""], Username, Hostname, [Port=22], Password)`：
        静态方法，获取 slurm 终端并且连接到终端，使用密码认证，认为最后一个输入的字符串是密码
     - **参数设置**
        - `setSleepTime(SleepTime)`：
        设置每轮提交任务或者检测任务完成情况的等待时间，单位 ms，默认为 500
        - `setTolerant(Tolerant)`：
        设置提交任务失败的尝试次数，默认为 3，注意网络连接问题导致的失败不包括在内（即网络连接失败会一直尝试重新连接）。
        必须是出现相同的失败情况超过 Tolerant 次后才会取消这个任务的提交，因此有可能出现死循环
        - `setMirror(Path)`：
        设置此对象的本地镜像，之后任何改动都会同步到本地的镜像上。可以通过 `load` 来重新加载这个镜像来继续操作
   - **基本方法**
        - `ssh()`：
        返回内部的 `ServerSSH` 实例，通过此实现一般的 ssh 操作
        - `shutdown()`：
        断开连接并且关闭此 ssh 终端，不再允许其他操作，在此之前会等待提交的任全部完成
        - `shutdownNow()`：
        立刻断开连接并且关闭此 ssh 终端，不再允许其他操作，不会等待任务完成且会强行取消掉正在执行的任务
        - `pause()`：
        暂停 slurm 端的任务提交以及任务完成情况的检测，如果正在提交任务会挂起程序直到提交完成，保证之后 slurm 端的任务队列不会发生改变
        - `unpause()`：
        解除暂停
        - `kill(Warning=true)`：
        直接杀死这个 slurm 端，类似于通过系统层面直接杀死（但是更加安全可控），会保留内部记录的正在执行的任务以及任务队列，
        在没有设置镜像时执行会提示警告，关闭 `Warning` 可以抑制这个警告
    - **任务提交**
        - `[task_]submitSystem([BeforeSystem], [AfterSystem], Command, [Partition], NodeNumber=1, OutputPath='.temp/slurm/out-%j')`：
        向 SLURM 服务器提交指令，相当于此指令写入了一个 bash 脚本并且使用 sbatch 来提交，
        输入 Task 对象来指定 `BeforeSystem` 和 `AfterSystem`，分别会在执行任务前和完成任务后执行这个 Task
        - `[task_]submitBash([BeforeSystem], [AfterSystem], BashPath, [Partition], NodeNumber=1, OutputPath='.temp/slurm/out-%j')`：
        向 SLURM 服务器直接提交 bash 脚本，相当于将本地的处于 `BashPath` 的脚本上传到远程服务器，然后使用 sbatch 来提交
        - `[task_]submitSrun([BeforeSystem], [AfterSystem], Command, [Partition], TaskNumber=1, MaxTaskNumberPerNode=20, OutputPath='.temp/slurm/out-%j')`：
        向 SLURM 服务器直接提交 srun 指令，相当于在指令前增加一个 srun 并且写入 bash 脚本然后用sbatch 来提交，会根据输入自动计算需要的节点数目
        - `[task_]submitSrunBash([BeforeSystem], [AfterSystem], BashPath, [Partition], TaskNumber=1, MaxTaskNumberPerNode=20, OutputPath='.temp/slurm/out-%j')`：
        向 SLURM 服务器直接提交 srun 运行的脚本，首先会将本地的处于 `BashPath` 的脚本上传到远程服务器，然后使用 srun 实行这个脚本，
        并且将此操作写入 bash 脚本然后使用 sbatch 来提交，会根据输入自动计算需要的节点数目
    - **实用方法**
        - `jobNumber()`：
        获取此用户在 SLURM 服务器上正在执行的任务数目
        - `jobIDs()`：
        获取此用户在 SLURM 服务器上正在执行的任务的编号
        - `[task_]cancelAll()`：
        取消此用户在 SLURM 服务器上正在执行的所有任务，即使这个任务不是通过这对象提交的。同时会清空此对象中排队的任务（如果有的话）
        - `[task_]cancelThis()`：
        取消这个对象提交的所有任务并且清空排队的任务
        - `undo()`：
        尝试取消最后一次提交的任务，如果在排队则取消成功返回对应的指令，如果已经提则取消失败，返回 null
        - `getActiveCount()`：
        获取正在执行的任务数目（仅限本对象提交的）
        - `getQueueSize()`：
        获取正在排队的任务数目
        - `waitUntilDone()`：
        挂起程序直到任务全部完成
        - `getTaskNumber()`：
        获取剩余任务数目，即 `getActiveCount() + getQueueSize()`
        - `awaitTermination()`：
        挂起直到内部线程池关闭（结合 `shutdown()` 使用）
        - `getActiveJobIDs()`：
        获取正在执行的任务编号（仅限本对象提交的）
        - `getQueueCommands()`：
        获取正在排队的指令列表
- **`code.UT`**：
实用工具类
    - `Pair`：
    在 java 中实现的类似于 c++ STL 中的 Pair 类
    - `Task`：
    通过重写 `run` 方法将 `ServerSSH` 或 `ServerSLURM` 中的一些操作封装成这个类，`run` 返回 `false` 或者报错都代表执行失败
    - `mergeTask(Task1, Task2)`：
    静态方法，将两个 Task 合并成一个 Task，先执行 `Task1` 后执行 `Task2`，其中出现任何执行失败都会中断后续执行
    - `tryTask(Task, [Tolerant])`：
    静态方法，尝试执行 `Task`，失败会返回 `false` 而不是报错，设置 `Tolerant` 会在失败后重新尝试执行 `Tolerant` 次


# 代码部分
本项目使用 [Gradle](https://gradle.org/) 进行管理，在这里可以不安装 Gradle

在 `include/java` 目录下，运行 `./gradlew build` 即可进行编译，默认会将 jar 文件输出到上级目录中

需要系统拥有 jdk，可以从 [这里](https://mirrors.tuna.tsinghua.edu.cn/Adoptium/) 下载 jdk（建议使用 jdk11 或者 jdk8 ）


# License
This code is licensed under the [MIT License](LICENSE).
