package com.kayo.infowall

/**
 * Обои для экрана блокировки: тот же летящий рельеф и цвет, что на рабочем столе,
 * но без часов, плашек, виджетов и касаний; камера выше и раскачивается сильнее — геометрии в кадре больше.
 */
class InfoLockWallpaperService : InfoWallpaperService() {
    override val lockMode = true
}
