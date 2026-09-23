package com.example.monitor.dto;

/**
 * OS のフォルダ選択ダイアログで選ばれたディレクトリの絶対パス。
 *
 * @param path 選ばれた絶対パス
 */
public record DirectoryPickResponse(String path) {}
