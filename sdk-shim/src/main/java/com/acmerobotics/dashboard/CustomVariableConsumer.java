package com.acmerobotics.dashboard;

import com.acmerobotics.dashboard.config.variable.CustomVariable;

/** Edits the dashboard's configuration tree. */
public interface CustomVariableConsumer {
    void accept(CustomVariable configRoot);
}
