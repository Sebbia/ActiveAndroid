package com.activeandroid;

import com.activeandroid.util.SQLiteUtils;

import java.util.List;

public abstract class View extends Model {
    public abstract String getQuery();

    @Override
    public void delete() {
        throw new RuntimeException("Cannot delete view row");
    }

    @Override
    public Long save() {
        throw new RuntimeException("Cannot save view row");
    }

    @SuppressWarnings("UnusedDeclaration")
    public static <E extends View> List<E> getRows(Class<E> viewClass, String... params) {
        String query;
        try {
            query = viewClass.newInstance().getQuery();
            return SQLiteUtils.rawQuery(viewClass, query, params);
        } catch (Exception e) {
            throw new RuntimeException("Cannot create view row.", e);
        }
    }
}
