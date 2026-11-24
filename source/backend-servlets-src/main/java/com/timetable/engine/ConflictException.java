package com.timetable.engine;

/** A booking was refused because a resource is already taken. Carries a suggested free alternative. */
public class ConflictException extends Exception {
    public final String type;
    public String suggestion;
    public Integer suggestionSlotId;

    public ConflictException(String type, String message) {
        super(message);
        this.type = type;
    }
}
