@echo off
chcp 65001 >nul
if not exist logs mkdir logs

echo === 启动 gateway ===
start /B java -jar gateway\target\dist\gateway.jar > logs\gateway.log 2>&1
timeout /t 10 /nobreak >nul

echo === 启动 member ===
start /B java -jar member\target\dist\member.jar > logs\member.log 2>&1
timeout /t 10 /nobreak >nul

echo === 启动 business ===
start /B java -jar business\target\dist\business.jar > logs\business.log 2>&1
timeout /t 10 /nobreak >nul

echo === 启动 batch ===
start /B java -jar batch\target\dist\batch.jar > logs\batch.log 2>&1

echo === 全部启动完成 ===
jps -l | findstr "gateway member business batch"