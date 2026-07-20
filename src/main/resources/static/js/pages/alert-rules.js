const numericRuleForm = document.getElementById('numericRuleForm');

if (numericRuleForm) {

    const sensorSelect = numericRuleForm.querySelector('[name="sensorId"]');

    const thresholdInput = numericRuleForm.querySelector('[name="thresholdValue"]');

    const unitWrapper = document.getElementById('numericThresholdUnitWrapper');

    const unitLabel = document.getElementById('numericThresholdUnit');

    function updateThresholdPresentation() {

        const selectedOption =sensorSelect.options[sensorSelect.selectedIndex];

        const sensorType = selectedOption?.dataset.sensorType ?? '';

        const unit = selectedOption?.dataset.unit ?? '';

        unitWrapper.hidden = unit === '';
        unitLabel.textContent = unit;

        if (sensorType === 'HUMIDITY') {
            thresholdInput.min = '0';
            thresholdInput.max = '100';
        } else {
            thresholdInput.removeAttribute('min');
            thresholdInput.removeAttribute('max');
        }
    }

    sensorSelect.addEventListener('change',updateThresholdPresentation);

    updateThresholdPresentation();
}