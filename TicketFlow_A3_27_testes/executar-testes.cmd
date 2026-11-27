@echo off
setlocal
chcp 65001 >nul
pushd "%~dp0" || exit /b 2

where java >nul 2>&1
if errorlevel 1 goto falta_jdk
where javac >nul 2>&1
if errorlevel 1 goto falta_jdk

if not exist "execucao\classes" mkdir "execucao\classes"
set "JUNIT_CP=lib/apiguardian-api-1.1.2.jar;lib/junit-jupiter-api-5.8.1.jar;lib/junit-jupiter-engine-5.8.1.jar;lib/junit-jupiter-params-5.8.1.jar;lib/junit-platform-commons-1.8.1.jar;lib/junit-platform-engine-1.8.1.jar;lib/opentest4j-1.2.0.jar"
echo Compilando os testes...
javac -source 17 -target 17 -Xlint:-options -encoding UTF-8 -cp "%JUNIT_CP%" -sourcepath "ticketflow/src/main/java" -d "execucao/classes" "execucao/ExecutarJUnit.java" "ticketflow/src/test/java/br/edu/ticketflow/service/RequisitosTicketFlowTest.java"
if errorlevel 1 goto falha_compilacao

echo Executando os 27 casos com o JUnit...
java -Dfile.encoding=UTF-8 -cp "execucao/classes;%JUNIT_CP%" ExecutarJUnit br.edu.ticketflow.service.RequisitosTicketFlowTest > "resultado-junit.txt" 2>&1
set "RESULTADO=%ERRORLEVEL%"
findstr /B /L /C:"Engine:" /C:"Java:" /C:"[SUCCESSFUL]" /C:"[FAILED]" /C:"[ABORTED]" /C:"Executados:" "resultado-junit.txt"
echo.
echo Relatorio completo: resultado-junit.txt
pause
popd
exit /b %RESULTADO%

:falta_jdk
echo O Java JDK 17 ou mais recente precisa estar no PATH.
echo No terminal, confira: java -version e javac -version
goto sair_com_erro

:falha_compilacao
echo A compilacao falhou. Confira as mensagens acima.
:sair_com_erro
pause
popd
exit /b 2
