from child_select import match_child_by_nickname


def test_matches_case_insensitive_with_whitespace():
    children = [
        {"cid": "c1", "nickname": "Alice"},
        {"cid": "c2", "nickname": " Test "},
    ]
    assert match_child_by_nickname(children, "test") == "c2"
    assert match_child_by_nickname(children, "ALICE") == "c1"


def test_no_match_and_missing_nicknames():
    children = [{"cid": "c1", "nickname": None}, {"cid": "c2"}]
    assert match_child_by_nickname(children, "test") is None
    assert match_child_by_nickname([], "test") is None


def test_accepts_objects():
    class Ref:
        cid = "c9"
        nickname = "Test"

    assert match_child_by_nickname([Ref()], "test") == "c9"
