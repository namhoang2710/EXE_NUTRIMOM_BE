# Load .env variables into current process session
if (Test-Path .env) {
    Get-Content .env | Where-Object { $_ -notmatch '^\s*#' -and $_ -match '=' } | ForEach-Object {
        $parts = $_ -split '=', 2
        if ($parts.Length -eq 2) {
            $key = $parts[0].Trim()
            $val = $parts[1].Trim()
            [System.Environment]::SetEnvironmentVariable($key, $val, 'Process')
        }
    }
    Write-Host "[NutriMom Backend] Đã nạp biến môi trường từ file .env thành công!" -ForegroundColor Green
} else {
    Write-Host "[NutriMom Backend] Cảnh báo: Không tìm thấy file .env, dùng cấu hình mặc định." -ForegroundColor Yellow
}

Write-Host "[NutriMom Backend] Đang khởi động Spring Boot trên cổng 8080..." -ForegroundColor Cyan
.\mvnw.cmd spring-boot:run
