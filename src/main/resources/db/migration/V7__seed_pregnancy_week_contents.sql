INSERT INTO app.pregnancy_week_contents (
    week,
    title,
    summary,
    baby_development,
    mother_changes,
    care_tips,
    warning_signs,
    sources,
    disclaimer,
    created_at,
    updated_at
)
SELECT
    s.week,
    CONCAT('Tuần thai kỳ ', s.week),
    CONCAT('Nội dung tổng quan cho tuần ', s.week, ' đang chờ đội ngũ chuyên môn y khoa của NutriMom thẩm định.'),
    CONCAT('Thông tin phát triển của em bé ở tuần ', s.week, ' đang chờ thẩm định y khoa trước khi phát hành nội dung chi tiết.'),
    CONCAT('Những thay đổi thường gặp của mẹ ở tuần ', s.week, ' có thể khác nhau giữa từng người và đang chờ thẩm định y khoa.'),
    'Duy trì lịch khám thai và trao đổi trực tiếp với bác sĩ hoặc cơ sở y tế về chế độ chăm sóc phù hợp với tình trạng cá nhân.',
    'Nếu xuất hiện triệu chứng bất thường, nghiêm trọng hoặc khiến bạn lo lắng, hãy liên hệ bác sĩ hoặc cơ sở y tế; không tự chẩn đoán từ nội dung này.',
    '[{"name":"World Health Organization - Pregnancy","url":"https://www.who.int/health-topics/pregnancy"}]',
    'Nội dung chỉ nhằm cung cấp thông tin tham khảo, đang chờ rà soát y khoa và không thay thế chẩn đoán hay chỉ định của bác sĩ. Hãy tham khảo bác sĩ hoặc cơ sở y tế để được tư vấn phù hợp.',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM generate_series(0, 42) AS s(week)
WHERE NOT EXISTS (
    SELECT 1
    FROM app.pregnancy_week_contents existing
    WHERE existing.week = s.week
);

