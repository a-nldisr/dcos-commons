import logging
import pytest

import sdk_cmd
import sdk_install
import sdk_plan
import sdk_utils

from tests import config


log = logging.getLogger(__name__)

foldered_name = sdk_utils.get_foldered_name(config.SERVICE_NAME)


@pytest.fixture(scope="module", autouse=True)
def configure_package(configure_security):
    try:
        sdk_install.uninstall(config.PACKAGE_NAME, foldered_name)

        yield  # let the test session execute
    finally:
        sdk_install.uninstall(config.PACKAGE_NAME, foldered_name)


@pytest.mark.sanity
def test_all_tasks_are_launched():
    service_options = {"service": {"yaml": "plan"}}
    sdk_install.install(
        config.PACKAGE_NAME,
        foldered_name,
        0,
        additional_options=service_options,
        wait_for_deployment=False,
        wait_for_all_conditions=True
    )
    # after above method returns, start all plans right away.
    plans = ["manual-plan-0", "manual-plan-1", "manual-plan-2"]
    for plan in plans:
        sdk_plan.start_plan(foldered_name, plan)
    for plan in plans:
        sdk_plan.wait_for_completed_plan(foldered_name, plan)
    pods = ["custom-pod-A-0", "custom-pod-B-0", "custom-pod-C-0"]
    for pod in pods:
        # /pod/<pod-id>/info fetches data from SDK's persistence layer
        pod_hello_0_info = sdk_cmd.service_request(
            "GET", foldered_name, "/v1/pod/{}/info".format(pod)
        ).json()
        for taskInfoAndStatus in pod_hello_0_info:
            info = taskInfoAndStatus["info"]
            status = taskInfoAndStatus["status"]
            # While `info` object is always present, `status` may or may not be present based
            # on whether the task was launched and we received an update from mesos (or not).
            if status:
                assert info["taskId"]["value"] == status["taskId"]["value"]
                assert len(info["taskId"]["value"]) > 0
            else:
                assert len(info["taskId"]["value"]) == 0
