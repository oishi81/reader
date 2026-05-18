@echo off
echo ============================================
echo  编译 BookSourceLoginController 到 reader
echo ============================================
echo.

REM 需要 javac，检查是否可用
where javac >nul 2>&1
if errorlevel 1 (
    echo [错误] 找不到 javac，需要安装 JDK
    echo 下载: https://adoptium.net/download/
    pause
    exit /b 1
)

REM 设置 classpath（从 reader 项目）
set CP=src\lib\rhino-1.7.13.jar;src\lib\xmlpull-1.1.3.4.jar
set OUT=build\login

echo [1/3] 创建输出目录...
mkdir %OUT% 2>nul

echo [2/3] 编译 BookSourceLoginController...
javac -d %OUT% -cp %CP% ^
  src\main\java\com\htmake\reader\api\controller\BookSourceLoginController.kt

if errorlevel 1 (
    echo [错误] 编译失败。需要 Kotlin 编译器才能编译 .kt 文件。
    echo.
    echo 替代方案：在 iStoreOS 上编译
    echo.
    echo docker exec -it myreader bash
    echo cd /tmp
    echo ... 下载 kotlinc ...
    pause
    exit /b 1
)

echo [3/3] 打包 JAR...
cd %OUT%
jar cvf book-source-login.jar com\htmake\reader\api\controller\*.class
cd ..\..

echo.
echo ============================================
echo  完成！JAR 文件: %OUT%\book-source-login.jar
echo ============================================
echo.
echo 然后上传到 iStoreOS:
echo   scp build\login\book-source-login.jar root@1.oishi.eu.org:/opt/reader/
echo.
echo 重启 reader 加载:
echo   ssh root@1.oishi.eu.org
echo   docker cp /opt/reader/book-source-login.jar myreader:/app/lib/book-source-login.jar
echo   docker restart myreader
pause