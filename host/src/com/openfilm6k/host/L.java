package com.openfilm6k.host;

import java.util.Locale;
/** in-app localization. Column order: zh-CN, zh-HK, en, ja, ko, de, fr, es, ru, pt */
public class L {
    public static final String[] CODES = {"zh-CN", "zh-HK", "en", "ja", "ko", "de", "fr", "es", "ru", "pt"};
    public static final String[] NAMES = {"简体中文", "繁體中文（香港）", "English", "日本語", "한국어", "Deutsch", "Français", "Español", "Русский", "Português"};
    static int idx = 3;   // default English
    static String lang = "en";

    static final String[][] T = {
        // ---- chrome / groups ----
        {"settings", "设置", "設定", "Settings", "設定", "설정", "Einstellungen", "Paramètres", "Ajustes", "Настройки", "Configurações"},
        {"groupFilm", "胶卷与场景", "菲林與場景", "Film & Scene", "フィルムとシーン", "필름과 장면", "Film & Szene", "Film et scène", "Película y escena", "Плёнка и сцена", "Filme e cena"},
        {"groupDN", "白天夜晚", "日 / 夜", "Day / Night", "昼と夜", "낮과 밤", "Tag & Nacht", "Jour / nuit", "Día y noche", "День и ночь", "Dia e noite"},
        {"groupNodes", "节点操作", "節點操作", "Node ops", "ノード操作", "노드 작업", "Knoten-Aktionen", "Opérations nœud", "Operaciones de nodos", "Операции узлов", "Operações de nós"},
        {"pipelineNodes", "管线节点", "流程節點", "Pipeline nodes", "パイプラインノード", "파이프라인 노드", "Pipeline-Knoten", "Nœuds de pipeline", "Nodos del pipeline", "Узлы конвейера", "Nós do pipeline"},
        {"langLabel", "语言", "語言", "Language", "言語", "언어", "Sprache", "Langue", "Idioma", "Язык", "Idioma"},
        // ---- day/night ----
        {"split", "白天夜晚分离", "日夜分離", "Day/night split", "昼夜分離", "낮밤 분리", "Tag/Nacht-Trennung", "Séparation jour/nuit", "Separación día/noche", "Разделение день/ночь", "Separação dia/noite"},
        {"tabDay", "☀白天", "☀日間", "☀ Day", "☀昼", "☀ 낮", "☀ Tag", "☀ Jour", "☀ Día", "☀ День", "☀ Dia"},
        {"tabNight", "🌙夜晚", "🌙夜間", "🌙 Night", "🌙夜", "🌙 밤", "🌙 Nacht", "🌙 Nuit", "🌙 Noche", "🌙 Ночь", "🌙 Noite"},
        {"tabPrev", "⏱昼夜预览", "⏱日夜預覽", "⏱ Day/Night preview", "⏱昼夜プレビュー", "⏱낮밤 미리보기", "⏱ Tag/Nacht-Vorschau", "⏱ Aperçu jour/nuit", "⏱ Vista día/noche", "⏱ Просмотр день/ночь", "⏱ Prévia dia/noite"},
        {"scoreLabel", "昼夜度 ", "日夜度 ", "Day/night ", "昼夜度 ", "낮밤 정도 ", "Tag/Nacht-Wert ", "Valeur jour/nuit ", "Día/noche ", "День/ночь ", "Dia/noite "},
        {"photoScore", "照片昼夜度: ", "相片日夜度: ", "Photo day/night: ", "写真の昼夜度: ", "사진 낮밤 점수: ", "Foto Tag/Nacht: ", "Photo jour/nuit : ", "Día/noche de la foto: ", "Оценка день/ночь фото: ", "Dia/noite da foto: "},
        {"splitDone", "已分离（保存后生效）: 当前参数复制到白天/夜晚两份", "已分離（儲存後生效）: 目前參數複製到日間/夜間兩份", "Split (applies on save): params copied to day/night", "分離済み（保存時に反映）: パラメータを昼/夜にコピー", "분리됨(저장 시 적용): 매개변수를 낮/밤으로 복사", "Getrennt (beim Speichern): Parameter auf Tag/Nacht kopiert", "Séparé (à la sauvegarde) : paramètres copiés jour/nuit", "Separado (al guardar): parámetros copiados a día/noche", "Разделено (при сохранении): параметры скопированы в день/ночь", "Separado (ao salvar): parâmetros copiados para dia/noite"},
        // ---- film row ----
        {"newFilm", "新建卷", "新增菲林", "New film", "新規フィルム", "새 필름", "Neuer Film", "Nouveau film", "Nueva película", "Новая плёнка", "Novo filme"},
        {"delFilm", "删除卷", "刪除菲林", "Delete film", "フィルム削除", "필름 삭제", "Film löschen", "Supprimer le film", "Eliminar película", "Удалить плёнку", "Excluir filme"},
        {"newFilmName", "输入新卷名", "輸入新菲林名稱", "Enter film name", "フィルム名を入力", "필름 이름 입력", "Filmnamen eingeben", "Nom du film", "Nombre de película", "Имя плёнки", "Nome do filme"},
        {"create", "创建", "建立", "Create", "作成", "만들기", "Erstellen", "Créer", "Crear", "Создать", "Criar"},
        {"btnDelete", "删除", "刪除", "Delete", "削除", "삭제", "Löschen", "Supprimer", "Eliminar", "Удалить", "Excluir"},
        {"btnCancel", "取消", "取消", "Cancel", "キャンセル", "취소", "Abbrechen", "Annuler", "Cancelar", "Отмена", "Cancelar"},
        {"noFilmToDelete", "没有可删除的卷", "沒有可刪除的菲林", "No film to delete", "削除できるフィルムがありません", "삭제할 필름 없음", "Kein Film zum Löschen", "Aucun film à supprimer", "No hay película que eliminar", "Нет плёнки для удаления", "Nenhum filme para excluir"},
        {"confirmDel", "确认删除 \"", "確認刪除 \"", "Delete \"", "削除してよろしいですか \"", "삭제 확인 \"", "Film löschen: \"", "Supprimer « ", "¿Eliminar «", "Удалить «", "Excluir «"},
        {"delCfgTail", "\" 的配置？(LUT 文件保留)", "\" 的設定？(LUT 檔案保留)", "\" config? (LUT file kept)", "\" の設定？（LUTファイルは保持）", "\" 설정? (LUT 파일 유지)", "\"-Konfiguration? (LUT bleibt)", " » la configuration ? (fichier LUT conservé)", "» la configuración? (archivo LUT se conserva)", "» конфигурацию? (LUT-файл сохранён)", "» a configuração? (arquivo LUT mantido)"},
        {"invalidName", "卷名无效", "菲林名稱無效", "Invalid name", "無効な名前", "잘못된 이름", "Ungültiger Name", "Nom invalide", "Nombre no válido", "Неверное имя", "Nome inválido"},
        {"createFail", "新建失败 ", "新建失敗 ", "Create failed ", "作成失敗 ", "생성 실패 ", "Fehler beim Erstellen ", "Échec de création ", "Error al crear ", "Ошибка создания ", "Falha ao criar "},
        {"exists", "已存在: ", "已存在: ", "Already exists: ", "既に存在: ", "이미 존재: ", "Existiert bereits: ", "Existe déjà : ", "Ya existe: ", "Уже существует: ", "Já existe: "},
        {"saveFilm", "保存到卷", "儲存至菲林", "Save to film", "フィルムに保存", "필름에 저장", "In Film speichern", "Enregistrer dans le film", "Guardar en película", "Сохранить в плёнку", "Salvar no filme"},
        {"saved", "已保存", "已儲存", "Saved", "保存しました", "저장됨", "Gespeichert", "Enregistré", "Guardado", "Сохранено", "Salvo"},
        {"savedSplit", "（含白天/夜晚两份）", "（含日間/夜間兩份）", " (incl. day/night)", "（昼/夜の2件を含む）", " (낮/밤 2세트 포함)", " (inkl. Tag/Nacht)", " (incl. jour/nuit)", " (incl. día/noche)", " (включая день/ночь)", " (incl. dia/noite)"},
        {"reload", "重读", "重讀", "Reload", "再読み込み", "다시 읽기", "Neu laden", "Recharger", "Recargar", "Перечитать", "Recarregar"},
        // ---- node ui ----
        {"addNode", "添加节点", "添加節點", "Add node", "ノード追加", "노드 추가", "Knoten hinzufügen", "Ajouter un nœud", "Añadir nodo", "Добавить узел", "Adicionar nó"},
        {"copyCfg", "⧉ 复制配置", "⧉ 複製配置", "⧉ Copy config", "⧉ 設定をコピー", "⧉ 구성 복사", "⧉ Konfig kopieren", "⧉ Copier config", "⧉ Copiar config", "⧉ Копировать конфиг", "⧉ Copiar config"},
        {"pasteCfg", "📋 粘贴配置", "📋 貼上配置", "📋 Paste config", "📋 設定を貼り付け", "📋 구성 붙여넣기", "📋 Konfig einfügen", "📋 Coller config", "📋 Pegar config", "📋 Вставить конфиг", "📋 Colar config"},
        {"clipEmpty", "剪贴板为空", "剪貼板是空的", "Clipboard empty", "クリップボードが空です", "클립보드 비어 있음", "Zwischenablage leer", "Presse-papiers vide", "Portapapeles vacío", "Буфер обмена пуст", "Área de transferência vazia"},
        {"copied", "已复制当前配置 (", "已複製目前配置 (", "Config copied (", "設定をコピーしました (", "구성 복사됨 (", "Konfig kopiert (", "Config copiée (", "Config copiada (", "Конфиг скопирован (", "Config copiada ("},
        {"nodesSuffix", " 节点)", " 節點)", " nodes)", " ノード)", "개 노드)", " Knoten)", " nœuds)", " nodos)", " узлов)", " nós)"},
        {"pasted", "已粘贴 (保存后才会写入)", "已貼上 (儲存後才會寫入)", "Pasted (written on save)", "貼り付け済み（保存時に書き込まれます）", "붙여넣음 (저장 시 반영)", "Eingefügt (wird beim Speichern geschrieben)", "Collé (écrit à la sauvegarde)", "Pegado (se escribe al guardar)", "Вставлено (запишется при сохранении)", "Colado (gravado ao salvar)"},
        {"hideNode", "隐藏此节点", "隱藏此節點", "Hide node", "ノードを非表示", "노드 숨기기", "Knoten ausblenden", "Masquer le nœud", "Ocultar nodo", "Скрыть узел", "Ocultar nó"},
        {"showNode", "显示此节点", "顯示此節點", "Show node", "ノードを表示", "노드 표시", "Knoten anzeigen", "Afficher le nœud", "Mostrar nodo", "Показать узел", "Mostrar nó"},
        {"lutFile", "LUT文件 ", "LUT檔案 ", "LUT file ", "LUTファイル ", "LUT 파일 ", "LUT-Datei ", "Fichier LUT ", "Archivo LUT ", "Файл LUT ", "Arquivo LUT "},
        {"blendLabel", "混合 ", "混合 ", "Blend ", "ブレンド ", "블렌드 ", "Blend ", "Mélange ", "Mezcla ", "Смешение ", "Mistura "},
        // ---- node type labels ----
        {"tLut", "LUT 颜色", "LUT 顏色", "LUT color", "LUTカラー", "LUT 색상", "LUT-Farbe", "Couleur LUT", "Color LUT", "Цвет LUT", "Cor LUT"},
        {"tGrade", "调色", "調色", "Grade", "調色", "그레이드", "Farbanpassung", "Étalonnage", "Gradación", "Градация", "Correção"},
        {"tGlow", "辉光", "輝光", "Glow", "グロー", "글로우", "Glow", "Lueur", "Resplandor", "Свечение", "Brilho"},
        {"tGrain", "颗粒", "顆粒", "Grain", "グレイン", "그레인", "Korn", "Grain", "Grano", "Зерно", "Grão"},
        {"tVig", "暗角", "暗角", "Vignette", "ヴィネット", "비네트", "Vignettage", "Vignettage", "Viñeta", "Виньетка", "Vinheta"},
        {"tSharp", "锐化", "銳化", "Sharpen", "シャープ", "샤픈", "Schärfen", "Netteté", "Nitidez", "Резкость", "Nitidez"},
        {"tOverlay", "叠层", "疊層", "Overlay", "オーバーレイ", "오버레이", "Overlay", "Incrustation", "Superposición", "Наложение", "Sobreposição"},
        {"tHue", "偏色", "偏色", "Hue shift", "色ずれ", "색조", "Hue-Shift", "Décalage teinte", "Cambio de tono", "Сдвиг оттенка", "Matiz"},
        // ---- params ----
        {"exposure", "曝光", "曝光", "Exposure", "露出", "노출", "Belichtung", "Exposition", "Exposición", "Экспозиция", "Exposição"},
        {"contrast", "对比", "對比", "Contrast", "コントラスト", "대비", "Kontrast", "Contraste", "Contraste", "Контраст", "Contraste"},
        {"saturation", "饱和", "飽和", "Saturation", "彩度", "채도", "Sättigung", "Saturation", "Saturación", "Насыщенность", "Saturação"},
        {"temp", "色温", "色溫", "Temp", "色温度", "색온도", "Farbtemperatur", "Température", "Temperatura", "Температура", "Temperatura"},
        {"tint", "色调", "色調", "Tint", "ティント", "틴트", "Tönung", "Teinte", "Tinte", "Оттенок", "Tonalidade"},
        {"low", "暗部", "暗部", "Shadows", "シャドウ", "어두운 부분", "Schwarz", "Noirs", "Sombras", "Тени", "Sombras"},
        {"mid", "中间调", "中間調", "Midtones", "中間調", "중간톤", "Mitten", "Moyens", "Tonos medios", "Полутона", "Tons médios"},
        {"high", "高光", "高光", "Highlights", "ハイライト", "밝은 부분", "Lichter", "Hautes", "Altas", "Света", "Altas luzes"},
        {"threshold", "阈值", "臨界值", "Threshold", "しきい値", "임계값", "Schwellwert", "Seuil", "Umbral", "Порог", "Limiar"},
        {"thresh2", "阈值上限", "臨界值上限", "Thresh high", "しきい値上限", "임계값 상한", "Schwelle hoch", "Seuil haut", "Umbral alto", "Порог высокий", "Limiar alto"},
        {"radius", "半径", "半徑", "Radius", "半径", "반지름", "Radius", "Rayon", "Radio", "Радиус", "Raio"},
        {"mode", "亮度/通道", "亮度/通道", "Luma/Channel", "輝度/チャンネル", "휘도/채널", "Luma/Kanal", "Luma/Canal", "Luma/Canal", "Люма/Канал", "Luma/Canal"},
        {"rmul", "红乘子", "紅乘子", "Red gain", "赤ゲイン", "빨강 게인", "Rot-Anteil", "Gain rouge", "Ganancia roja", "Красн. gain", "Ganho vermelho"},
        {"gmul", "绿乘子", "綠乘子", "Green gain", "緑ゲイン", "초록 게인", "Grün-Anteil", "Gain vert", "Ganancia verde", "Зел. gain", "Ganho verde"},
        {"bmul", "蓝乘子", "藍乘子", "Blue gain", "青ゲイン", "파랑 게인", "Blau-Anteil", "Gain bleu", "Ganancia azul", "Син. gain", "Ganho azul"},
        {"cmix", "染色比例", "染色比例", "Tint mix", "染色比率", "색조 비율", "Färbung", "Mix teinte", "Mezcla tinte", "Смешение", "Mistura tinta"},
        {"peak", "峰值归一", "峰值歸一", "Peak norm", "ピーク正規化", "피크 정규화", "Peak-Norm", "Norm. crête", "Norm. pico", "Норм. пиков", "Norm. pico"},
        {"grainSize", "颗粒尺寸", "顆粒尺寸", "Grain size", "グレインサイズ", "그레인 크기", "Korngröße", "Taille grain", "Tamaño grano", "Размер зерна", "Tamanho grão"},
        {"mono", "单色", "單色", "Mono", "モノクロ", "모노", "Mono", "Mono", "Mono", "Моно", "Mono"},
        {"start", "起点", "起點", "Start", "開始", "시작", "Start", "Début", "Inicio", "Начало", "Início"},
        {"end", "终点", "終點", "End", "終了", "끝", "Ende", "Fin", "Fin", "Конец", "Fim"},
        {"red", "红", "紅", "Red", "赤", "빨강", "Rot", "Rouge", "Rojo", "Красный", "Vermelho"},
        {"green", "绿", "綠", "Green", "緑", "초록", "Grün", "Vert", "Verde", "Зелёный", "Verde"},
        {"blue", "蓝", "藍", "Blue", "青", "파랑", "Blau", "Bleu", "Azul", "Синий", "Azul"},
        {"amount", "强度", "強度", "Amount", "強度", "강도", "Stärke", "Intensité", "Intensidad", "Интенсивн.", "Intensidade"},
        {"scale", "缩放", "縮放", "Scale", "スケール", "배율", "Skalierung", "Échelle", "Escala", "Масштаб", "Escala"},
        {"hue", "色相", "色相", "Hue", "色相", "색상", "Farbton", "Teinte", "Tono", "Оттенок", "Matiz"},
        {"hueLabel", "色相 ", "色相 ", "Hue ", "色相 ", "색상 ", "Farbton ", "Teinte ", "Tono ", "Оттенок ", "Matiz "},
        {"range", "范围", "範圍", "Range", "範囲", "범위", "Bereich", "Plage", "Rango", "Диапазон", "Faixa"},
        {"shift", "偏移", "偏移", "Shift", "シフト", "시프트", "Verschiebung", "Décalage", "Desplaz.", "Сдвиг", "Desloc."},
        {"satLabel", "饱和度", "飽和度", "Saturation", "飽和度", "채도", "Sättigung", "Saturation", "Saturación", "Насыщенность", "Saturação"},
        {"zone", "作用区 ", "作用區 ", "Zone ", "領域 ", "영역 ", "Zone ", "Zone ", "Zona ", "Зона ", "Zona "},
        // ---- blend modes ----
        {"bmNormal", "正常", "正常", "Normal", "通常", "일반", "Normal", "Normal", "Normal", "Обычный", "Normal"},
        {"bmScreen", "屏幕", "螢幕", "Screen", "スクリーン", "스크린", "Negativ mult.", "Superposition", "Trama", "Экран", "Tela"},
        {"bmMultiply", "正片叠底", "正片疊底", "Multiply", "乗算", "곱하기", "Multiplizieren", "Produit", "Multiplicar", "Умножение", "Multiplicar"},
        {"bmOverlay", "叠加", "疊加", "Overlay", "オーバーレイ", "오버레이", "Inkopieren", "Incrustation", "Superponer", "Перекрытие", "Sobrepor"},
        {"bmSoft", "柔光", "柔光", "Soft light", "ソフトライト", "부드러운 빛", "Weiches Licht", "Lumière tamisée", "Luz suave", "Мягкий свет", "Luz suave"},
        // ---- hue presets ----
        {"cSkyblue", "天蓝", "天藍", "Sky blue", "空色", "하늘색", "Himmelblau", "Bleu ciel", "Azul cielo", "Голубой", "Azul céu"},
        {"cCyan", "青", "青", "Cyan", "シアン", "청록", "Cyan", "Cyan", "Cian", "Циан", "Ciano"},
        {"cTeal", "青绿", "青綠", "Teal", "青緑", "청녹", "Türkis", "Sarcelle", "Verde azulado", "Бирюзовый", "Verde-água"},
        {"cChartreuse", "黄绿", "黃綠", "Chartreuse", "黄緑", "연두", "Gelbgrün", "Vert pomme", "Verde amarillento", "Салатовый", "Verde-limão"},
        {"cYellow", "黄", "黃", "Yellow", "黄", "노랑", "Gelb", "Jaune", "Amarillo", "Жёлтый", "Amarelo"},
        {"cOrange", "橙", "橙", "Orange", "オレンジ", "주황", "Orange", "Orange", "Naranja", "Оранжевый", "Laranja"},
        {"cRose", "玫红", "玫紅", "Rose", "ローズ", "로즈", "Rosé", "Rose", "Rosa", "Розовый", "Rosa"},
        {"cMagenta", "洋红", "洋紅", "Magenta", "マゼンタ", "마젠타", "Magenta", "Magenta", "Magenta", "Пурпурный", "Magenta"},
        {"cPurple", "紫", "紫", "Purple", "紫", "보라", "Purpur", "Violet", "Morado", "Фиолетовый", "Roxo"},
        {"cViolet", "蓝紫", "藍紫", "Violet", "青紫", "청보라", "Violett", "Bleu-violet", "Violeta", "Сине-фиолетовый", "Violeta"},
        // ---- overlay ----
        {"ovPool", "叠层池（随机选一生效）", "疊層池（隨機選一生效）", "Overlay pool (one picked)", "オーバーレイプール（ランダム1枚）", "오버레이 풀(무작위 1장)", "Overlay-Pool (eines zufällig)", "Pool d'overlays (un au hasard)", "Conjunto de superposiciones", "Пул наложений (случайное)", "Conjunto de sobreposições"},
        {"ovEmpty", "叠层池空：把 .jpg 放入 /sdcard/OpenFilm6K/overlays/", "疊層池空：把 .jpg 放入 /sdcard/OpenFilm6K/overlays/", "Pool empty: put .jpg into /sdcard/OpenFilm6K/overlays/", "プール空：.jpg を /sdcard/OpenFilm6K/overlays/ へ", "풀 비음: .jpg를 /sdcard/OpenFilm6K/overlays/에 넣으세요", "Pool leer: .jpg nach /sdcard/OpenFilm6K/overlays/", "Pool vide : mettre .jpg dans /sdcard/OpenFilm6K/overlays/", "Vacío: pon .jpg en /sdcard/OpenFilm6K/overlays/", "Пул пуст: положите .jpg в /sdcard/OpenFilm6K/overlays/", "Vazio: coloque .jpg em /sdcard/OpenFilm6K/overlays/"},
        // ---- preview labels ----
        {"origLabel", "← 原图", "← 原圖", "← Original", "← 原画", "← 원본", "← Original", "← Original", "← Original", "← Оригинал", "← Original"},
        {"adjLabel", "成图 →", "成圖 →", "Result →", "完成画像 →", "결과 →", "Ergebnis →", "Résultat →", "Resultado →", "Результат →", "Resultado →"},
        // ---- settings dialog ----
        {"customScene", "自定义参考图", "自訂參考相片", "Custom reference photos", "カスタム参照画像", "사용자 참조 사진", "Eigene Referenzfotos", "Photos de référence perso.", "Fotos de referencia propias", "Свои опорные фото", "Fotos de referência próprias"},
        {"customLut", "自定义LUT", "自訂LUT", "Custom LUTs", "カスタムLUT", "사용자 LUT", "Eigene LUTs", "LUT perso.", "LUT propias", "Свои LUT", "LUT próprias"},
        {"noneItem", "（无）", "（無）", "(none)", "（なし）", "(없음)", "(keine)", "(aucun)", "(ninguno)", "(нет)", "(nenhum)"},
        {"noDelTarget", "没有可删除项", "沒有可刪除項", "Nothing to delete", "削除対象がありません", "삭제할 항목 없음", "Nichts zu löschen", "Rien à supprimer", "Nada que eliminar", "Нечего удалять", "Nada para excluir"},
        {"deleted", "已删除 ", "已刪除 ", "Deleted ", "削除しました ", "삭제됨 ", "Gelöscht ", "Supprimé ", "Eliminado ", "Удалено ", "Excluído "},
        {"imported", "已导入 ", "已匯入 ", "Imported ", "インポートしました ", "가져옴 ", "Importiert ", "Importé ", "Importado ", "Импортировано ", "Importado "},
        {"importFail", "导入失败 ", "匯入失敗 ", "Import failed ", "インポート失敗 ", "가져오기 실패 ", "Import fehlgeschlagen ", "Échec d'import ", "Error al importar ", "Ошибка импорта ", "Falha na importação "},
        {"notCube", "不是 .cube 文件: ", "不是 .cube 檔案: ", "Not a .cube file: ", ".cube ファイルではありません: ", ".cube 파일이 아님: ", "Keine .cube-Datei: ", "Pas un fichier .cube : ", "No es un archivo .cube: ", "Не файл .cube: ", "Não é um arquivo .cube: "},
        {"notJpg", "不是 jpg 图片: ", "不是 jpg 相片: ", "Not a jpg image: ", "jpg 画像ではありません: ", "jpg 이미지가 아님: ", "Kein jpg-Bild: ", "Pas une image jpg : ", "No es una imagen jpg: ", "Не jpg-изображение: ", "Não é uma imagem jpg: "},
        {"officialFilm", "官方卷不可删除", "官方菲林不可刪除", "Official films cannot be deleted", "公式フィルムは削除できません", "공식 필름은 삭제할 수 없습니다", "Offizielle Filme können nicht gelöscht werden", "Les films officiels ne peuvent pas être supprimés", "No se pueden eliminar las películas oficiales", "Официальные плёнки нельзя удалять", "Filmes oficiais não podem ser excluídos"},
        {"filmName", "卷名", "菲林名稱", "Film name", "フィルム名", "필름 이름", "Filmname", "Nom du film", "Nombre", "Имя плёнки", "Nome"},
        {"blankFilm", "新建空白卷", "新增空白菲林", "Blank film", "白紙のフィルム", "빈 필름", "Leerer Film", "Film vierge", "Película en blanco", "Пустая плёнка", "Filme em branco"},
        {"copyCurFilm", "复制当前卷", "複製目前菲林", "Copy current film", "現在のフィルムを複製", "현재 필름 복사", "Aktuellen Film kopieren", "Copier le film actuel", "Copiar película actual", "Копировать текущую", "Copiar filme atual"},
        {"createMode", "新建方式", "新增方式", "Create mode", "作成方法", "생성 방식", "Erstellungsart", "Mode de création", "Modo de creación", "Способ создания", "Modo de criação"},
        {"langChangedToast", "语言已切换", "語言已切換", "Language switched", "言語を切り替えました", "언어 변경됨", "Sprache gewechselt", "Langue changée", "Idioma cambiado", "Язык изменён", "Idioma alterado"},
        // ---- preview watermark ----
        {"pvStampLabel", "预览水印", "預覽水印", "Preview stamp", "プレビュー水印", "미리보기 워터마크", "Vorschau-Wasserzeichen", "Filigrane d'aperçu", "Marca de vista previa", "Водяной знак превью", "Marca d'água"},
        // ---- spy mode ----
        {"spyMode", "监视模式", "監視模式", "Spy mode", "スパイモード", "스파이 모드", "Spionmodus", "Mode espion", "Modo espía", "Режим слежки", "Modo espião"},
        {"spyEnable", "开启监视模式", "開啟監視模式", "Enable spy mode", "スパイモードを有効化", "감시 모드 활성화", "Spionmodus aktivieren", "Activer le mode espion", "Activar modo espía", "Включить режим слежки", "Ativar modo espião"},
        {"spyFilm", "胶卷", "膠卷", "Film", "フィルム", "필름", "Film", "Pellicule", "Película", "Плёнка", "Filme"},
        {"spyStamp", "水印与边框", "水印與邊框", "Watermark & frame", "ウォーターマークと枠", "워터마크 및 프레임", "Wasserzeichen & Rahmen", "Filigrane et cadre", "Marca y marco", "Водяной знак и рамка", "Marca e moldura"},
        {"spyDirs", "监视目录", "監視目錄", "Watched folders", "監視フォルダ", "감시 폴더", "Überwachte Ordner", "Dossiers surveillés", "Carpetas vigiladas", "Отслеживаемые папки", "Pastas vigiadas"},
        {"stPolaroid", "拍立得边框", "拍立得邊框", "Polaroid frame", "ポラロイド枠", "폴라로이드 프레임", "Polaroid-Rahmen", "Cadre polaroïde", "Marco polaroid", "Рамка Polaroid", "Moldura polaroid"},
        {"stCollage", "四宫格拼贴", "四宮格拼貼", "4-shot collage", "4コマコラージュ", "4컷 콜라주", "4er-Collage", "Collage 4 vues", "Collage de 4", "Коллаж 4 кадра", "Colagem 4 fotos"},
        {"stNone", "无", "無", "None", "なし", "없음", "Keine", "Aucun", "Ninguna", "Нет", "Nenhuma"},
        {"stD", "日期", "日期", "Date", "日付", "날짜", "Datum", "Date", "Fecha", "Дата", "Data"},
        {"stE", "曝光", "曝光", "Exposure", "露出", "노출", "Belichtung", "Exposition", "Exposición", "Экспозиция", "Exposição"},
        {"stDE", "日期+曝光", "日期+曝光", "Date + exposure", "日付+露出", "날짜+노출", "Datum + Belichtung", "Date + exposition", "Fecha + exposición", "Дата + экспозиция", "Data + exposição"},
        {"stF", "卷名", "菲林名稱", "Film name", "フィルム名", "필름 이름", "Filmname", "Nom du film", "Nombre de película", "Имя плёнки", "Nome do filme"},
        {"stFE", "卷名+曝光", "菲林名稱+曝光", "Film + exposure", "フィルム+露出", "필름+노출", "Film + Belichtung", "Film + exposition", "Película + exposición", "Плёнка + экспозиция", "Filme + exposição"},
        {"btnClose", "关闭", "關閉", "Close", "閉じる", "닫기", "Schließen", "Fermer", "Cerrar", "Закрыть", "Fechar"},
        {"btnSave", "保存", "儲存", "Save", "保存", "저장", "Speichern", "Enregistrer", "Guardar", "Сохранить", "Salvar"},
        {"btnOk", "确定", "確定", "OK", "OK", "확인", "OK", "OK", "Aceptar", "ОК", "OK"},
        {"btnImport", "导入", "匯入", "Import", "インポート", "가져오기", "Importieren", "Importer", "Importar", "Импорт", "Importar"},
        {"dataInstalling", "正在安装官方数据…", "正在安裝官方資料…", "Installing film library…", "公式データをインストール中…", "공식 데이터 설치 중…", "Installiere Filmdaten…", "Installation des films…", "Instalando el catálogo…", "Установка плёнок…", "Instalando os filmes…"},
        {"installDone", "已安装 ", "已安裝 ", "Installed ", "インストール完了 ", "설치 완료 ", "Installiert: ", "Installé : ", "Instalados ", "Установлено ", "Instalados "},
        {"filesUnit", " 个文件", " 個檔案", " files", " ファイル", "개 파일", " Dateien", " fichiers", " archivos", " файлов", " arquivos"},
        {"installFail", "安装失败", "安裝失敗", "Install failed", "インストール失敗", "설치 실패", "Installation fehlgeschlagen", "Échec de l'installation", "Error de instalación", "Ошибка установки", "Falha na instalação"},
        {"permTitle", "需要存储权限", "需要儲存權限", "Storage permission", "ストレージ権限", "저장소 권한", "Speicherberechtigung", "Autorisation de stockage", "Permiso de almacenamiento", "Разрешение на хранилище", "Permissão de armazenamento"},
        {"permMsg", "OpenFilm6K 需要“所有文件访问”权限来保存胶卷数据和成片，请在下一页授予。", "OpenFilm6K 需要「所有檔案存取」權限來保存膠卷資料和成片，請在下一頁授予。", "OpenFilm6K needs All-Files-Access to store film data and graded photos. Please grant it on the next screen.", "OpenFilm6K はフィルムデータと写真の保存に「すべてのファイルアクセス」権限が必要です。次の画面で許可してください。", "OpenFilm6K는 필름 데이터와 사진 저장을 위해 모든 파일 접근 권한이 필요합니다. 다음 화면에서 허용해 주세요.", "OpenFilm6K benötigt Zugriff auf alle Dateien, um Filmdaten und Fotos zu speichern. Bitte im nächsten Bildschirm erlauben.", "OpenFilm6K nécessite l'accès à tous les fichiers pour stocker les films et les photos. Veuillez l'accorder sur l'écran suivant.", "OpenFilm6K necesita acceso a todos los archivos para guardar los datos y las fotos. Conciédalo en la siguiente pantalla.", "OpenFilm6K нужен доступ ко всем файлам для хранения плёнок и снимков. Разрешите на следующем экране.", "O OpenFilm6K precisa de acesso a todos os arquivos para salvar filmes e fotos. Conceda na próxima tela."},
        {"editHint", "输入新卷名", "輸入新卷名", "Film name", "フィルム名", "필름 이름", "Filmname", "Nom du film", "Nombre de película", "Название плёнки", "Nome do filme"},
        {"reinstallData", "重新安装数据", "重新安裝資料", "Reinstall data", "データ再インストール", "데이터 재설치", "Daten neu installieren", "Réinstaller les données", "Reinstalar datos", "Переустановить данные", "Reinstalar dados"},
        {"annTitle", "标注 ", "標註 ", "Annotation ", "注釈 ", "주석 ", "Anmerkung ", "Annotation ", "Anotación ", "Аннотация ", "Anotação "},
        {"annHint", "个标注", "個標註", "annotations", "件の注釈", "개 주석", "Anmerkungen", "annotations", "anotaciones", "аннотаций", "anotações"},
        {"annHintText", "输入标注文字", "輸入標註文字", "Annotation text", "注釈テキスト", "주석 텍스트", "Anmerkungstext", "Texte de l'annotation", "Texto de anotación", "Текст аннотации", "Texto da anotação"},
        {"authorLabel", "作者 ", "作者 ", "Author ", "作者 ", "제작자 ", "Autor ", "Auteur ", "Autor ", "Автор ", "Autor "},
        {"aboutTitle", "OpenFilm6K", "OpenFilm6K", "OpenFilm6K", "OpenFilm6K", "OpenFilm6K", "OpenFilm6K", "OpenFilm6K", "OpenFilm6K", "OpenFilm6K", "OpenFilm6K"},
        {"aboutDesc", "OpenFilm6K —— 为索尼 A6000 打造的开源胶片模拟系统。\n拍摄 → 传入手机 → 自动完成 LUT、辉光、颗粒、暗角渲染。\n内置多款胶卷，支持自定义 LUT 与节点式编辑。\n以 GPL-3.0 开源，欢迎交流与改进。", "OpenFilm6K —— 為索尼 A6000 打造的開源膠片模擬系統。\n拍攝 → 傳入手機 → 自動完成 LUT、輝光、顆粒、暗角渲染。\n內建多款膠卷，支援自訂 LUT 與節點式編輯。\n以 GPL-3.0 開源，歡迎交流與改進。", "OpenFilm6K — an open-source film simulation system for the Sony A6000.\nShoot → phone ingest → automatic LUT, halation, grain and vignette rendering.\nShips with a set of film sims; bring your own LUTs and edit node by node.\nGPL-3.0 open source — contributions welcome.", "OpenFilm6K —— Sony A6000 向けオープンソースのフィルムシミュレーション。\n撮影 → スマホ受信 → LUT・ハレーション・グレイン・ヴィネットを自動レンダリング。\n多数のフィルムを内蔵、独自 LUT とノード編集に対応。\nGPL-3.0 のオープンソース。", "OpenFilm6K —— Sony A6000용 오픈소스 필름 시뮬레이션 시스템.\n촬영 → 폰 전송 → LUT, 헤일레이션, 그레인, 비네트 자동 렌더링.\n다양한 필름 내장, 커스텀 LUT 및 노드 편집 지원.\nGPL-3.0 오픈소스.", "OpenFilm6K — Open-Source-Filmsimulation für die Sony A6000.\nAufnehmen → ans Handy senden → automatische LUT-, Halation-, Korn- und Vignetten-Renderung.\nMit Filmsimulationen, eigenen LUTs und Node-Editing.\nGPL-3.0 Open Source.", "OpenFilm6K — système open source de simulation de pellicule pour Sony A6000.\nPrise de vue → envoi vers le téléphone → rendu automatique LUT, halo, grain, vignettage.\nNombre de films intégrés, LUT personnalisées et édition par nœuds.\nOpen source GPL-3.0.", "OpenFilm6K — sistema open source de simulación de película para la Sony A6000.\nDispara → envía al teléfono → renderizado automático de LUT, halo, grano y viñeta.\nIncluye varias películas; LUT propios y edición por nodos.\nCódigo abierto GPL-3.0.", "OpenFilm6K —— опенсорсная система имитации плёнки для Sony A6000.\nСъёмка → передача на телефон → автоматический рендеринг LUT, ореолов, зерна и виньетирования.\nМножество встроенных плёнок, свои LUT и нодовое редактирование.\nОткрытый код GPL-3.0.", "OpenFilm6K — sistema open source de simulação de filme para a Sony A6000.\nAtire → envie para o telefone → renderização automática de LUT, halation, grão e vinheta.\nVários filmes embutidos, LUTs próprias e edição por nós.\nCódigo aberto GPL-3.0."},
    };

    /** system scene display names, row = {tag, zh-CN, zh-TW, en, ja, ko, de, fr, es, ru, pt} */
    static final String[][] SCENES = {
        {"bar1", "酒吧", "酒吧", "Bar", "バー", "바", "Bar", "Bar", "Bar", "Бар", "Bar"},
        {"beach1", "海滩", "海灘", "Beach", "ビーチ", "해변", "Strand", "Plage", "Playa", "Пляж", "Praia"},
        {"beach2", "海岸", "海岸", "Coast", "海岸", "해안", "Küste", "Côte", "Costa", "Побережье", "Costa"},
        {"colors", "色卡", "色卡", "Color chart", "カラーチャート", "컬러차트", "Farbkarte", "Charte couleurs", "Carta de color", "Цветовая карта", "Carta de cores"},
        {"dusk1", "黄昏", "黃昏", "Dusk", "夕暮れ", "해질녘", "Dämmerung", "Crépuscule", "Atardecer", "Сумерки", "Crepúsculo"},
        {"dusk2", "落日", "落日", "Sunset", "夕日", "석양", "Sonnenuntergang", "Coucher de soleil", "Puesta de sol", "Закат", "Pôr do sol"},
        {"food1", "美食", "美食", "Food", "グルメ", "음식", "Essen", "Gastronomie", "Comida", "Еда", "Comida"},
        {"green1", "绿意", "綠意", "Green", "緑", "초록", "Grün", "Vert", "Verde", "Зелень", "Verde"},
        {"green2", "森林", "森林", "Forest", "森", "숲", "Wald", "Forêt", "Bosque", "Лес", "Floresta"},
        {"indoor1", "室内", "室內", "Indoor", "室内", "실내", "Innenraum", "Intérieur", "Interior", "Интерьер", "Interior"},
        {"night1", "霓虹夜", "霓虹夜", "Neon night", "ネオンの夜", "네온 밤", "Neonnacht", "Nuit néon", "Noche de neón", "Неоновая ночь", "Noite neon"},
        {"night2", "街灯夜", "街燈夜", "Street-lit night", "街灯の夜", "가로등 밤", "Straßenlaternen-Nacht", "Nuit éclairée", "Noche urbana", "Ночь фонарей", "Noite urbana"},
        {"night3", "银河", "銀河", "Milky Way", "天の川", "은하수", "Milchstraße", "Voie lactée", "Vía Láctea", "Млечный Путь", "Via Láctea"},
        {"portrait1", "人像", "人像", "Portrait", "ポートレート", "인물", "Porträt", "Portrait", "Retrato", "Портрет", "Retrato"},
        {"portrait2", "特写", "特寫", "Close-up", "クローズアップ", "클로즈업", "Nahaufnahme", "Gros plan", "Primer plano", "Крупный план", "Primeiro plano"},
        {"red1", "红", "紅", "Red", "赤", "빨강", "Rot", "Rouge", "Rojo", "Красный", "Vermelho"},
        {"red2", "绯红", "緋紅", "Crimson", "紅", "진홍", "Karmin", "Cramoisi", "Carmesí", "Багровый", "Carmim"},
        {"sky1", "天空", "天空", "Sky", "空", "하늘", "Himmel", "Ciel", "Cielo", "Небо", "Céu"},
        {"sky2", "云", "雲", "Clouds", "雲", "구름", "Wolken", "Nuages", "Nubes", "Облака", "Nuvens"},
        {"street1", "街景", "街景", "Street", "街並み", "거리", "Straße", "Rue", "Calle", "Улица", "Rua"},
        {"street2", "商街", "商街", "Shopping street", "商店街", "상점가", "Einkaufsstraße", "Rue commerçante", "Calle comercial", "Торговая улица", "Rua comercial"},
    };

    static int col(String code) {
        if ("zh-TW".equals(code)) code = "zh-HK";   // legacy saved pref
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(code)) return i + 1;
        return 3;   // fallback English
    }

    /** resolve saved pref, or default from system locale */
    public static void init(android.content.Context c) {
        String saved = c.getSharedPreferences("of6k", 0).getString("lang", null);
        if (saved == null) {
            saved = "en";   // fallback English
            String l = Locale.getDefault().toLanguageTag();
            if (l.equalsIgnoreCase("zh-TW") || l.equalsIgnoreCase("zh-HK") || l.equalsIgnoreCase("zh-Hant")) saved = "zh-HK";
            else {
                String base = l.split("-")[0];
                for (String k : CODES) if (k.equals(base)) { saved = base; break; }
            }
        }
        lang = saved;
        idx = col(saved);
    }

    public static String s(String k) {
        for (String[] row : T) if (row[0].equals(k)) {
            String v = row[idx];
            if (v == null || v.isEmpty()) v = row[3];   // fallback English
            return v;
        }
        return k;
    }

    public static String scene(String tag) {
        for (String[] row : SCENES) if (row[0].equals(tag)) return row[idx];
        return tag;   // custom scenes show their file name untranslated
    }

    /** reverse: display name -> scene tag (custom names pass through) */
    public static String tagOf(String display) {
        for (String[] row : SCENES) if (row[idx].equals(display)) return row[0];
        return display;
    }

    public static void setLang(android.content.Context c, String code) {
        c.getSharedPreferences("of6k", 0).edit().putString("lang", code).commit();
        lang = code;
        idx = col(code);
    }
}
