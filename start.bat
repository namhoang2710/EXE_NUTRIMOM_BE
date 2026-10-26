@echo off
chcp 65001 > nul
echo [NutriMom Backend] Đang khởi động Spring Boot...
if exist .env (
    for /f "usebackq tokens=1,* delims==" %%A in (".env") do (
        set "line=%%A"
        if not "!line:~0,1!"=="#" (
            set "%%A=%%B"
        )
    )
)
mvnw.cmd spring-boot:run
