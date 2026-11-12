package domain

func ValidPage(page, size int) bool { return page >= 1 && page <= 1000000 && size >= 1 && size <= 100 }

func ValidTarget(kind string) bool { return kind == "comic" || kind == "chapter" || kind == "media" }
