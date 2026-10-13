# Hand Tracking + Sable (NeoForge 1.21.1)

Мод НЕ запустится без Sable (обязательная зависимость в neoforge.mods.toml).

## Сначала проверьте Sable на телефоне (без моего мода!)
1. Zalith: установить NeoForge 1.21.1, положить в mods: Sable (NeoForge 1.21.1) и всё, что он требует (смотрите страницу Sable на Modrinth).
2. Создать креатив-мир, поставить блок и выполнить: /sable assemble area X Y Z X Y Z (координаты блока) - блок должен стать физическим и упасть.
3. Если игра вылетает с "Sable linking with Rapier natives" - Sable не работает на вашем телефоне, дальше смысла нет.

## Заливка/сборка
Те же шаги: git push -> Actions -> Releases (handtrack-0.2.0.jar, app-debug.apk).

## Игра
Hand Sender -> Запустить -> Zalith -> одиночный мир. Щипок большим+указательным на блоке/мобе.
Блок станет физическим телом Sable. Настройки: GrabController.java (PHYS_VEL_PER_BLOCK_PER_TICK и др.).
