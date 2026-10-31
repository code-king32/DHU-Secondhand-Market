package com.dhu.secondhand.common;

public final class TextValidation {
    private TextValidation() { }

    public static boolean isBlank(String value) {
        return value == null || value.codePoints().allMatch(character -> Character.isWhitespace(character)
                || Character.isSpaceChar(character) || Character.getType(character) == Character.FORMAT);
    }
}
