package com.enginertugrul.iotsensormonitor.entity.alert;

public enum ComparisonOperator {

    ABOVE {
        @Override
        public boolean matches(Double value, Double threshold) {
            return value != null && threshold != null && value > threshold;
        }
    },

    BELOW {
        @Override
        public boolean matches(Double value, Double threshold) {
            return value != null && threshold != null && value < threshold;
        }

    };

    public abstract boolean matches(Double value , Double threshold);

}
